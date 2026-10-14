package com.dont39.pz.zbktutils.processor

import com.google.devtools.ksp.KspExperimental
import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSNode
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSValueParameter
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.symbol.Variance
import com.pzlauncher.zabric.processor.glue.GlueWriter
import com.pzlauncher.zabric.processor.glue.HookAnalyzer
import com.pzlauncher.zabric.processor.glue.Messages
import com.pzlauncher.zabric.processor.glue.Names
import com.pzlauncher.zabric.processor.glue.Reporter

private const val LUA_GLOBAL = "com.dont39.pz.zbktutils.glue.LuaGlobal"
private const val LUA_METHOD = "se.krka.kahlua.integration.annotations.LuaMethod"
private const val FEATURE = "com.dont39.pz.zbktutils.Feature"

private val LUA_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")

/** Set by pz-feature.gradle.kts. */
private const val OPTION_FEATURE = "dont39.feature"
private const val OPTION_PACKAGE = "dont39.gluePackage"
private const val OPTION_LUA_PREFIX = "dont39.luaPrefix"
private val OPTIONS =
  listOf(OPTION_FEATURE, OPTION_PACKAGE, OPTION_LUA_PREFIX, Names.OPTION_MOD_ID, Names.OPTION_GLUE_CLASS)

/** Kotlin front end of Zabric's hook generator (the same glue as Java mods get), plus the Lua bridge. */
@OptIn(KspExperimental::class)
class GlueProcessor(
  private val codeGenerator: CodeGenerator,
  private val logger: KSPLogger,
  private val options: Map<String, String>,
) : SymbolProcessor {
  private var firstRound = true
  private var failed = false

  /** Outer glue classes written here; their @Patch classes are not the author's. */
  private val generated = mutableSetOf<String>()

  override fun process(resolver: Resolver): List<KSAnnotated> {
    checkLoaderPatches(resolver)
    checkStray(resolver)
    val hookObjects = resolver.getSymbolsWithAnnotation(Names.HOOKS).filterIsInstance<KSClassDeclaration>().toList()
    if (!firstRound) {
      // one glue class per compilation
      hookObjects.forEach {
        val name = it.qualifiedName?.asString()
        error(it, "$name: @Hooks classes generated in a later processing round are not supported")
      }
      return emptyList()
    }
    firstRound = false
    val missing = OPTIONS.filter { options[it].isNullOrBlank() }
    if (missing.isNotEmpty()) {
      missing.forEach { error(null, "KSP option $it is missing; apply the buildsrc.convention.pz-feature plugin") }
      return emptyList()
    }
    val pkg = options.getValue(OPTION_PACKAGE)
    if (hookObjects.isNotEmpty()) {
      hooks(resolver, hookObjects, pkg)
    }
    lua(resolver, pkg)
    return emptyList()
  }

  // ---- hand-written loader patches ----

  private fun checkLoaderPatches(resolver: Resolver) {
    resolver.getSymbolsWithAnnotation(Names.PATCH).filterIsInstance<KSClassDeclaration>().forEach { type ->
      if (outermost(type).qualifiedName?.asString() in generated) return@forEach
      val name = type.qualifiedName?.asString() ?: type.simpleName.asString()
      if (type.annotation(Names.UNSAFE_LOADER_PATCH) == null) {
        error(type, Messages.loaderPatch(name))
      } else {
        logger.warn(Messages.unsafeLoaderPatch(name), type)
      }
    }
    resolver.getSymbolsWithAnnotation(Names.UNSAFE_LOADER_PATCH).filterIsInstance<KSClassDeclaration>().forEach {
      if (it.annotation(Names.PATCH) == null) {
        error(it, Messages.markerWithoutPatch(it.qualifiedName?.asString() ?: it.simpleName.asString()))
      }
    }
  }

  private fun outermost(type: KSClassDeclaration): KSDeclaration =
    generateSequence<KSDeclaration>(type) { it.parentDeclaration }.last()

  /** Handler annotations outside hook objects, parameter annotations outside handlers. */
  private fun checkStray(resolver: Resolver) {
    HANDLER_KINDS.forEach { (annotation, kind) ->
      resolver.getSymbolsWithAnnotation(annotation).filterIsInstance<KSFunctionDeclaration>().forEach {
        val parent = it.parentDeclaration
        if (parent?.annotation(Names.HOOKS) == null) {
          error(it, Messages.handlerOutsideHooks(label(it), kind.annotation))
        }
      }
    }
    PARAM_ANNOTATIONS.forEach { annotation ->
      resolver.getSymbolsWithAnnotation(annotation).filterIsInstance<KSValueParameter>().forEach {
        val function = it.parent as? KSFunctionDeclaration
        if (function == null || HANDLER_KINDS.keys.none { handler -> function.annotation(handler) != null }) {
          val method = function?.let(::label) ?: it.name?.asString() ?: "?"
          error(it, Messages.parameterOutsideHandler(method, "@" + annotation.substringAfterLast('.')))
        }
      }
    }
  }

  private fun label(function: KSFunctionDeclaration): String {
    val parent = function.parentDeclaration
    val simple = function.simpleName.asString()
    return if (parent == null) simple else "${parent.simpleName.asString()}.$simple"
  }

  // ---- hooks ----

  private fun hooks(resolver: Resolver, hookObjects: List<KSClassDeclaration>, pkg: String) {
    val types = KspTypes(resolver)
    val facade by lazy { facade(resolver, hookObjects.first()) }
    val reader = HookReader(resolver, types, { facade }, ::error)
    // the others are still analyzed, so one compilation reports every problem
    val decls = hookObjects.mapNotNull(reader::read)
    val reporter = object : Reporter {
      override fun error(node: Any?, message: String) = this@GlueProcessor.error(node as? KSNode, message)

      override fun warning(node: Any?, message: String) = logger.warn(message, node as? KSNode)
    }
    val unit = HookAnalyzer(types, reporter)
      .analyze(options.getValue(Names.OPTION_MOD_ID), pkg, options.getValue(Names.OPTION_GLUE_CLASS), decls)
    if (unit == null || failed) return
    val files = (hookObjects + reader.gates).mapNotNull { it.containingFile }.distinct()
    write(Dependencies(true, *files.toTypedArray()), pkg, unit.className(), "java", GlueWriter.source(unit))
    GlueWriter.listings(unit).forEach { (path, text) ->
      codeGenerator.createNewFileByPath(Dependencies(true, *files.toTypedArray()), path.removeSuffix(".txt"), "txt")
        .use { it.write(text.toByteArray()) }
    }
    generated += unit.qualifiedName()
  }

  /** The module's one top-level object extending [FEATURE], the gate of hook objects that name none. */
  private fun facade(resolver: Resolver, node: KSClassDeclaration): KSClassDeclaration? {
    val facades = resolver.getAllFiles()
      .flatMap { it.declarations }
      .filterIsInstance<KSClassDeclaration>()
      .filter { it.classKind == ClassKind.OBJECT && it.getAllSuperTypes().any { s -> s.isType(FEATURE) } }
      .toList()
    if (facades.size != 1) {
      val found = if (facades.isEmpty()) "none in this module" else "this module has " +
        facades.joinToString { it.qualifiedName!!.asString() }
      error(
        node,
        "hook objects without a gate are gated by the feature's facade, the one top-level object extending " +
          "$FEATURE; $found",
      )
      return null
    }
    return facades.single()
  }

  // ---- Lua ----

  private class LuaParam(val name: String, val type: String)

  private class LuaFunction(
    val name: String,
    val call: String,
    val label: String,
    val file: KSFile?,
    val params: List<LuaParam>,
    val returnType: String?,
  )

  private fun lua(resolver: Resolver, pkg: String) {
    val prefix = options.getValue(OPTION_LUA_PREFIX)
    val functions = collectLua(resolver, prefix)
    if (failed || functions.isEmpty()) return
    val feature = options.getValue(OPTION_FEATURE)
    val bridge = "${feature}Lua"
    val files = functions.mapNotNull { it.file }.distinct()
    write(Dependencies(true, *files.toTypedArray()), pkg, bridge, "kt", luaSource(pkg, bridge, functions))
    val lines = listOf("lua $pkg.$bridge") + functions.map { "luaGlobal ${it.name}" }
    codeGenerator.createNewFileByPath(Dependencies(true, *files.toTypedArray()), "META-INF/zbkt/glue/$feature", "txt")
      .use { it.write(lines.joinToString("") { line -> "$line\n" }.toByteArray()) }
  }

  private fun collectLua(resolver: Resolver, prefix: String): List<LuaFunction> {
    val byName = linkedMapOf<String, LuaFunction>()
    val functions =
      resolver.getSymbolsWithAnnotation(LUA_GLOBAL, inDepth = true).filterIsInstance<KSFunctionDeclaration>()
    for (fn in functions) {
      val label = label(fn)
      val call = luaCall(fn, label) ?: continue
      val name = fn.annotation(LUA_GLOBAL)!!.arg("name") as String
      val problem = when {
        !LUA_NAME.matches(name) -> "Lua name \"$name\" is not a Lua identifier"
        !name.startsWith(prefix) || name == prefix ->
          "Lua name \"$name\" must start with \"$prefix\" (Lua globals are shared by all mods)"
        name in byName -> "Lua name \"$name\" is already used by ${byName.getValue(name).label}"
        fn.parameters.any { it.isVararg } -> "vararg parameters are not supported"
        else -> null
      }
      if (problem != null) {
        error(fn, "$label: $problem")
        continue
      }
      val params = fn.parameters.map { LuaParam(it.name?.asString() ?: "p", render(it.type.resolve())) }
      val returnType = fn.returnType?.resolve()?.takeUnless { it.isType("kotlin.Unit") }?.let(::render)
      byName[name] = LuaFunction(name, call, label, fn.containingFile, params, returnType)
    }
    return byName.values.sortedBy { it.name }
  }

  /** The function as a Kotlin expression without arguments, or null after an error. */
  private fun luaCall(fn: KSFunctionDeclaration, label: String): String? {
    val parent = fn.parentDeclaration
    val simple = fn.simpleName.asString()
    fun fail(problem: String): String? {
      error(fn, "$label: $problem")
      return null
    }
    val owner = when {
      parent == null -> fn.packageName.asString()
      parent is KSClassDeclaration && parent.classKind == ClassKind.OBJECT && !parent.isCompanionObject &&
        parent.parentDeclaration == null -> parent.qualifiedName!!.asString()
      else -> return fail(
        "@LuaGlobal needs a member of a top-level object or a top-level function (the generated bridge calls it " +
          "without a receiver); not a class or companion member, nested or local function",
      )
    }
    if (fn.extensionReceiver != null) return fail("@LuaGlobal cannot be on an extension function")
    if (fn.typeParameters.isNotEmpty()) return fail("@LuaGlobal cannot be on a generic function")
    if (Modifier.SUSPEND in fn.modifiers) return fail("@LuaGlobal cannot be on a suspend function")
    if (fn.getVisibility().hidden()) {
      return fail("@LuaGlobal needs a public or internal function (the generated bridge calls it)")
    }
    if (parent != null && parent.getVisibility().hidden()) {
      return fail("@LuaGlobal needs a public or internal object (the generated bridge calls it)")
    }
    return if (owner.isEmpty()) simple else "$owner.$simple"
  }

  private fun luaSource(pkg: String, bridge: String, functions: List<LuaFunction>) = buildString {
    appendLine("// Generated by zb-kt-utils-processor from @LuaGlobal. Do not edit.")
    appendLine("package $pkg")
    appendLine()
    appendLine("import $LUA_METHOD")
    appendLine()
    appendLine("// ZombieBuddy exposes global Lua functions only from classes in exactly javaPkgName; Kahlua calls")
    appendLine("// them on an instance ZombieBuddy creates through the private constructor.")
    appendLine("object $bridge {")
    functions.forEachIndexed { i, function ->
      if (i > 0) appendLine()
      val params = function.params.joinToString(", ") { "${it.name}: ${it.type}" }
      val call = "${function.call}(${function.params.joinToString(", ") { it.name }})"
      appendLine("  @LuaMethod(name = ${kotlinString(function.name)}, global = true)")
      if (function.returnType == null) {
        appendLine("  fun ${function.name}($params) {")
        appendLine("    $call")
      } else {
        appendLine("  fun ${function.name}($params): ${function.returnType} {")
        appendLine("    return $call")
      }
      appendLine("  }")
    }
    appendLine("}")
  }

  // ---- shared ----

  private fun write(dependencies: Dependencies, pkg: String, name: String, extension: String, source: String) {
    codeGenerator.createNewFile(dependencies, pkg, name, extension).use { it.write(source.toByteArray()) }
  }

  private fun error(node: KSNode?, message: String) {
    failed = true
    logger.error(message, node)
  }
}

private fun KSType?.isType(qualifiedName: String) =
  this != null && !isMarkedNullable && declaration.qualifiedName?.asString() == qualifiedName

/** The type as Kotlin source, fully qualified. */
internal fun render(type: KSType): String {
  val base = type.declaration.qualifiedName?.asString() ?: type.declaration.simpleName.asString()
  val args = if (type.arguments.isEmpty()) {
    ""
  } else {
    type.arguments.joinToString(", ", "<", ">") {
      when (it.variance) {
        Variance.STAR -> "*"
        Variance.COVARIANT -> "out ${render(it.type!!.resolve())}"
        Variance.CONTRAVARIANT -> "in ${render(it.type!!.resolve())}"
        Variance.INVARIANT -> render(it.type!!.resolve())
      }
    }
  }
  return base + args + if (type.isMarkedNullable) "?" else ""
}

private fun kotlinString(text: String) = buildString {
  append('"')
  text.forEach {
    when (it) {
      '\\' -> append("\\\\")
      '"' -> append("\\\"")
      '$' -> append("\\$")
      '\n' -> append("\\n")
      else -> append(it)
    }
  }
  append('"')
}
