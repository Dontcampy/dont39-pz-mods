package com.dont39.pz.zbktutils.processor

import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.symbol.Variance
import com.google.devtools.ksp.symbol.Visibility

private const val GLUE = "com.dont39.pz.zbktutils.glue"
private const val HOOK = "$GLUE.Hook"
private const val ENTER = "$HOOK.Enter"
private const val EXIT = "$HOOK.Exit"
private const val LUA_GLOBAL = "$GLUE.LuaGlobal"
private const val PATCH = "me.zed_0xff.zombie_buddy.Patch"
private const val LUA_METHOD = "se.krka.kahlua.integration.annotations.LuaMethod"

/** `Hook.<name>` parameter bindings, each generated as ZombieBuddy's `Patch.<name>`. */
private val BINDINGS = setOf("Argument", "This", "Return", "Thrown", "AllArguments")
private val BINDING_ANNOTATIONS = BINDINGS.map { "$HOOK.$it" }.toSet()
private val EXIT_ONLY_BINDINGS = setOf("Return", "Thrown")
private const val NO_EXCEPTION = "$PATCH.NoException"

/** Kotlin types that compile to JVM primitives, so never null. */
private val PRIMITIVES = setOf("Boolean", "Byte", "Short", "Char", "Int", "Long", "Float", "Double").map { "kotlin.$it" }
private val LUA_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")

/** Set by pz-feature.gradle.kts. */
private const val OPTION_FEATURE = "dont39.feature"
private const val OPTION_PACKAGE = "dont39.gluePackage"
private const val OPTION_LUA_PREFIX = "dont39.luaPrefix"

/**
 * Generates a feature module's ZombieBuddy glue from the loader-neutral `glue` annotations; besides Zabric's loader
 * package, the only code that names the loader's API. Also writes a resource listing the glue and its hook targets,
 * shipped for the hook checks at runtime (HookTargets.kt) and read by the aggregating mod's checks and smokeTest. Rule
 * violations are compile errors naming the function.
 */
class GlueProcessor(
  private val codeGenerator: CodeGenerator,
  private val logger: KSPLogger,
  private val options: Map<String, String>,
) : SymbolProcessor {
  private var done = false
  private var failed = false

  override fun process(resolver: Resolver): List<KSAnnotated> {
    // The generated code carries no glue annotations, so one round sees everything.
    if (done) return emptyList()
    done = true
    val feature = option(OPTION_FEATURE)
    val pkg = option(OPTION_PACKAGE)
    val luaPrefix = option(OPTION_LUA_PREFIX)
    if (feature == null || pkg == null || luaPrefix == null) return emptyList()

    val targets = collectHooks(resolver)
    val luaFunctions = collectLua(resolver, luaPrefix)
    if (failed) return emptyList()

    val lines = mutableListOf<String>()
    if (targets.isNotEmpty()) {
      val outer = "${feature}Patches"
      write(pkg, outer, patchesSource(pkg, outer, targets), targets.flatMap { it.files() })
      targets.forEach { lines += "patch $pkg.$outer$${it.nestedName}" }
      targets.map { it.className }.distinct().forEach { lines += "target $it" }
      targets.forEach { lines += "hook ${it.className} ${it.methodName}" }
    }
    if (luaFunctions.isNotEmpty()) {
      val bridge = "${feature}Lua"
      write(pkg, bridge, luaSource(pkg, bridge, luaFunctions), luaFunctions.mapNotNull { it.callee.file })
      lines += "lua $pkg.$bridge"
      luaFunctions.forEach { lines += "luaGlobal ${it.name}" }
    }
    // also when empty: the runtime check treats a missing listing as a packaging error
    val files = (targets.flatMap { it.files() } + luaFunctions.mapNotNull { it.callee.file }).distinct()
    val dependencies = Dependencies(true, *files.toTypedArray())
    codeGenerator.createNewFileByPath(dependencies, "META-INF/zbkt/glue/$feature", "txt")
      .use { it.write(lines.joinToString("") { line -> "$line\n" }.toByteArray()) }
    return emptyList()
  }

  private fun option(name: String): String? = options[name].also {
    if (it.isNullOrBlank()) error("KSP option $name is missing; apply the buildsrc.convention.pz-feature plugin")
  }

  // ---- hooks ----

  private class Advice(val callee: Callee, val params: List<Param>, val returnType: String?, val annotation: String)

  private class Target(val className: String, val methodName: String, val strictMatch: Boolean, val warmUp: Boolean) {
    val nestedName = identifier(className.substringAfterLast('.')) + "_" + identifier(methodName)
    var enter: Advice? = null
    var exit: Advice? = null
    fun files() = listOfNotNull(enter?.callee?.file, exit?.callee?.file)
  }

  private fun collectHooks(resolver: Resolver): List<Target> {
    val targets = linkedMapOf<Pair<String, String>, Target>()
    for ((annotationName, isExit) in listOf(ENTER to false, EXIT to true)) {
      val kind = if (isExit) "Hook.Exit" else "Hook.Enter"
      for (fn in functionsWith(resolver, annotationName)) {
        val annotation = fn.annotations.first { it.qualifiedName() == annotationName }
        val callee = callee(fn, kind) ?: continue
        val className = annotation.arg("className") as String
        val methodName = annotation.arg("methodName") as String
        val strictMatch = annotation.arg("strictMatch") as? Boolean ?: false
        val warmUp = annotation.arg("warmUp") as? Boolean ?: false
        if (className.isBlank() || methodName.isBlank()) {
          error(callee, "@$kind needs a className and a methodName")
          continue
        }
        val catches = !isExit || annotation.arg("onThrow") as? Boolean ?: true
        val params = hookParams(fn, callee, isExit, catches) ?: continue
        val returnType = fn.returnType?.resolve()
        val advice = if (isExit) {
          if (!returnType.isType("kotlin.Unit")) {
            error(callee, "@Hook.Exit must return Unit (found ${returnType?.let(::render)})")
            continue
          }
          val thrown = if (catches) "kotlin.Throwable" else NO_EXCEPTION
          Advice(callee, params, null, "@Patch.OnExit(onThrowable = $thrown::class)")
        } else {
          val skipOn = annotation.arg("skipOn") as? Boolean ?: false
          if (skipOn && !returnType.isType("kotlin.Boolean")) {
            val found = returnType?.let(::render)
            error(callee, "@Hook.Enter(skipOn = true) must return Boolean, true skipping the target (found $found)")
            continue
          }
          if (!skipOn && !returnType.isType("kotlin.Unit")) {
            val found = returnType?.let(::render)
            error(callee, "@Hook.Enter must return Unit, or Boolean with skipOn = true (found $found)")
            continue
          }
          if (skipOn) {
            Advice(callee, params, "kotlin.Boolean", "@Patch.OnEnter(skipOn = true)")
          } else {
            Advice(callee, params, null, "@Patch.OnEnter")
          }
        }

        val target = targets.getOrPut(className to methodName) { Target(className, methodName, strictMatch, warmUp) }
        val existing = if (isExit) target.exit else target.enter
        if (existing != null) {
          val other = existing.callee.label
          error(callee, "$className.$methodName already has a @$kind in this feature ($other); combine them")
          continue
        }
        if (target.strictMatch != strictMatch || target.warmUp != warmUp) {
          val other = (target.enter ?: target.exit)!!.callee.label
          error(callee, "strictMatch and warmUp must match the other hook on $className.$methodName ($other)")
          continue
        }
        if (isExit) target.exit = advice else target.enter = advice
      }
    }
    targets.values.groupBy { it.nestedName }.filterValues { it.size > 1 }.forEach { (name, clashing) ->
      val label = clashing[1].let { (it.enter ?: it.exit)!!.callee }
      error(
        label,
        "hooks on ${clashing.joinToString(" and ") { "${it.className}.${it.methodName}" }} would both generate the " +
          "patch class $name",
      )
    }
    return targets.values.sortedBy { it.nestedName }
  }

  private class Param(val annotation: String, val name: String, val type: String)

  private fun hookParams(fn: KSFunctionDeclaration, callee: Callee, isExit: Boolean, catches: Boolean): List<Param>? {
    val params = fn.parameters.map { p ->
      val name = p.name?.asString() ?: "p"
      val type = p.type.resolve()
      val bindings = p.annotations.filter { it.qualifiedName() in BINDING_ANNOTATIONS }.toList()
      val binding = bindings.singleOrNull()
      val kind = binding?.qualifiedName()?.removePrefix("$HOOK.")
      val problem = when {
        p.isVararg -> "parameter $name: vararg is not supported; use @Hook.AllArguments with an Array"
        binding == null -> "parameter $name needs exactly one of " +
          BINDINGS.joinToString { "@Hook.$it" } + " (found ${bindings.size})"
        kind in EXIT_ONLY_BINDINGS && !isExit -> "parameter $name: @Hook.$kind is only available in a @Hook.Exit"
        kind == "Thrown" && !catches ->
          "parameter $name: @Hook.Thrown needs the exit to run on throw; remove onThrow = false"
        else -> bindingTypeProblem(kind!!, name, type)
      }
      if (problem != null) {
        error(callee, problem)
        return@map null
      }
      val annotation = if (kind == "Argument") "@Patch.Argument(${binding!!.arg("index")})" else "@Patch.$kind"
      Param(annotation, name, render(type))
    }
    return if (null in params) null else params.filterNotNull()
  }

  /**
   * The target's own types are unknown here, so only nullability is checked: the advice is inlined into the target, and
   * Kotlin's parameter null check would throw there.
   */
  private fun bindingTypeProblem(kind: String, name: String, type: KSType): String? {
    val rendered = render(type)
    return when (kind) {
      "Thrown" -> if (rendered != "kotlin.Throwable?" && rendered != "kotlin.Any?") {
        "parameter $name: @Hook.Thrown must be Throwable? (null when the target returns normally), found $rendered"
      } else {
        null
      }
      "AllArguments" -> if (rendered.removeSuffix("?") != "kotlin.Array<kotlin.Any?>") {
        "parameter $name: @Hook.AllArguments must be Array<Any?> (boxed arguments, any may be null), found $rendered"
      } else {
        null
      }
      "Argument", "Return" -> if (!type.isMarkedNullable && rendered !in PRIMITIVES) {
        "parameter $name: @Hook.$kind of a reference type must be nullable ($rendered?): the target may pass null, " +
          "and the null check would throw inside the target"
      } else {
        null
      }
      else -> null
    }
  }

  private fun patchesSource(pkg: String, outer: String, targets: List<Target>) = buildString {
    appendLine("// Generated by zb-kt-utils-processor from @Hook.Enter / @Hook.Exit. Do not edit.")
    appendLine("package $pkg")
    appendLine()
    appendLine("import $PATCH")
    appendLine()
    appendLine("// ZombieBuddy picks up @Patch classes only in exactly javaPkgName and inlines the static advice.")
    appendLine("object $outer {")
    targets.forEachIndexed { i, target ->
      if (i > 0) appendLine()
      val extra = buildString {
        if (target.strictMatch) append(", strictMatch = true")
        if (target.warmUp) append(", warmUp = true")
      }
      val className = kotlinString(target.className)
      val methodName = kotlinString(target.methodName)
      appendLine("  @Patch(className = $className, methodName = $methodName$extra)")
      appendLine("  object ${target.nestedName} {")
      target.enter?.let { appendAdvice(it, "enter") }
      if (target.enter != null && target.exit != null) appendLine()
      target.exit?.let { appendAdvice(it, "exit") }
      appendLine("  }")
    }
    appendLine("}")
  }

  private fun StringBuilder.appendAdvice(advice: Advice, name: String) {
    val params = advice.params.joinToString(", ") { "${it.annotation} ${it.name}: ${it.type}" }
    val call = "${advice.callee.call}(${advice.params.joinToString(", ") { it.name }})"
    appendLine("    @JvmStatic")
    appendLine("    ${advice.annotation}")
    if (advice.returnType == null) {
      appendLine("    fun $name($params) {")
      appendLine("      $call")
    } else {
      appendLine("    fun $name($params): ${advice.returnType} {")
      appendLine("      return $call")
    }
    appendLine("    }")
  }

  // ---- Lua ----

  private class LuaFunction(val name: String, val callee: Callee, val params: List<Param>, val returnType: String?)

  private fun collectLua(resolver: Resolver, prefix: String): List<LuaFunction> {
    val byName = linkedMapOf<String, LuaFunction>()
    for (fn in functionsWith(resolver, LUA_GLOBAL)) {
      val callee = callee(fn, "LuaGlobal") ?: continue
      val name = fn.annotations.first { it.qualifiedName() == LUA_GLOBAL }.arg("name") as String
      val problem = when {
        !LUA_NAME.matches(name) -> "Lua name \"$name\" is not a Lua identifier"
        !name.startsWith(prefix) || name == prefix ->
          "Lua name \"$name\" must start with \"$prefix\" (Lua globals are shared by all mods)"
        name in byName -> "Lua name \"$name\" is already used by ${byName.getValue(name).callee.label}"
        fn.parameters.any { it.isVararg } -> "vararg parameters are not supported"
        else -> null
      }
      if (problem != null) {
        error(callee, problem)
        continue
      }
      val params = fn.parameters.map { Param("", it.name?.asString() ?: "p", render(it.type.resolve())) }
      val returnType = fn.returnType?.resolve()?.takeUnless { it.isType("kotlin.Unit") }?.let(::render)
      byName[name] = LuaFunction(name, callee, params, returnType)
    }
    return byName.values.sortedBy { it.name }
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
      val call = "${function.callee.call}(${function.params.joinToString(", ") { it.name }})"
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

  /** The annotated function: [call] for generated code, [label] for errors. */
  private class Callee(val call: String, val label: String, val file: KSFile?, val node: KSFunctionDeclaration)

  private fun callee(fn: KSFunctionDeclaration, kind: String): Callee? {
    val parent = fn.parentDeclaration
    val simple = fn.simpleName.asString()
    val label = if (parent == null) simple else "${parent.simpleName.asString()}.$simple"
    fun fail(problem: String): Callee? {
      error(fn, "$label: $problem")
      return null
    }
    val owner = when {
      parent == null -> fn.packageName.asString()
      parent is KSClassDeclaration && parent.classKind == ClassKind.OBJECT && !parent.isCompanionObject &&
        parent.parentDeclaration == null -> parent.qualifiedName!!.asString()
      else -> return fail(
        "@$kind needs a member of a top-level object or a top-level function (the generated glue calls it without " +
          "a receiver); not a class or companion member, nested or local function",
      )
    }
    if (fn.extensionReceiver != null) return fail("@$kind cannot be on an extension function")
    if (fn.typeParameters.isNotEmpty()) return fail("@$kind cannot be on a generic function")
    if (Modifier.SUSPEND in fn.modifiers) return fail("@$kind cannot be on a suspend function")
    if (fn.getVisibility().hidden()) {
      return fail("@$kind needs a public or internal function (the generated glue and the inlined advice call it)")
    }
    if (parent != null && parent.getVisibility().hidden()) {
      return fail("@$kind needs a public or internal object (the generated glue and the inlined advice call it)")
    }
    val call = if (owner.isEmpty()) simple else "$owner.$simple"
    return Callee(call, label, fn.containingFile, fn)
  }

  private fun Visibility.hidden() =
    this == Visibility.PRIVATE || this == Visibility.PROTECTED || this == Visibility.LOCAL

  private fun functionsWith(resolver: Resolver, annotation: String): List<KSFunctionDeclaration> =
    resolver.getSymbolsWithAnnotation(annotation, inDepth = true).filterIsInstance<KSFunctionDeclaration>().toList()

  private fun write(pkg: String, name: String, source: String, files: List<KSFile>) {
    codeGenerator.createNewFile(Dependencies(true, *files.distinct().toTypedArray()), pkg, name)
      .use { it.write(source.toByteArray()) }
  }

  private fun error(callee: Callee, problem: String) = error(callee.node, "${callee.label}: $problem")

  private fun error(node: KSAnnotated, message: String) {
    failed = true
    logger.error(message, node)
  }

  private fun error(message: String) {
    failed = true
    logger.error(message)
  }
}

private fun KSAnnotation.qualifiedName(): String =
  annotationType.resolve().declaration.qualifiedName?.asString() ?: shortName.asString()

private fun KSAnnotation.arg(name: String): Any? {
  val argument = arguments.firstOrNull { it.name?.asString() == name }
    ?: defaultArguments.firstOrNull { it.name?.asString() == name }
  return argument?.value
}

private fun KSType?.isType(qualifiedName: String) =
  this != null && !isMarkedNullable && declaration.qualifiedName?.asString() == qualifiedName

/** The type as Kotlin source, fully qualified. */
private fun render(type: KSType): String {
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

/** `<init>` becomes `_init_`. */
private fun identifier(text: String) = text.map { if (it.isLetterOrDigit() || it == '_') it else '_' }.joinToString("")

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
