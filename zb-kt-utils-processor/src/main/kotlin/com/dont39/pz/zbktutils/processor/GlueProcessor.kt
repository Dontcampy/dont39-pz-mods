package com.dont39.pz.zbktutils.processor

import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSNode
import com.google.devtools.ksp.symbol.KSValueParameter
import com.pzlauncher.zabric.processor.glue.GlueWriter
import com.pzlauncher.zabric.processor.glue.HookAnalyzer
import com.pzlauncher.zabric.processor.glue.Messages
import com.pzlauncher.zabric.processor.glue.Names
import com.pzlauncher.zabric.processor.glue.Reporter

private const val FEATURE = "com.dont39.pz.zbktutils.Feature"

/** Set by pz-feature.gradle.kts. */
private const val OPTION_FEATURE = "dont39.feature"
private const val OPTION_PACKAGE = "dont39.gluePackage"
private const val OPTION_LUA_PREFIX = "dont39.luaPrefix"
private val OPTIONS =
  listOf(OPTION_FEATURE, OPTION_PACKAGE, OPTION_LUA_PREFIX, Names.OPTION_MOD_ID, Names.OPTION_GLUE_CLASS)

/** Kotlin front end of Zabric's hook generator (the same glue as Java mods get), plus the Lua bridge. */
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
        error(it, Messages.laterRound(it.qualifiedName?.asString().toString()))
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

  // ---- hooks ----

  private fun hooks(resolver: Resolver, hookObjects: List<KSClassDeclaration>, pkg: String) {
    val types = KspTypes(resolver)
    val facade by lazy { facade(resolver, hookObjects.first()) }
    val reader = HookReader(resolver, types, { facade }, ::error)
    // the others are still analyzed, so one compilation reports every problem
    val decls = hookObjects.mapNotNull(reader::read)
    val reporter = Reporter { node, message -> error(node as? KSNode, message) }
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

  private fun lua(resolver: Resolver, pkg: String) {
    val functions = LuaBridge(options.getValue(OPTION_LUA_PREFIX), ::error).collect(resolver)
    if (failed || functions.isEmpty()) return
    val feature = options.getValue(OPTION_FEATURE)
    val bridge = "${feature}Lua"
    val files = functions.mapNotNull { it.file }.distinct()
    write(Dependencies(true, *files.toTypedArray()), pkg, bridge, "kt", luaSource(pkg, bridge, functions))
    codeGenerator.createNewFileByPath(Dependencies(true, *files.toTypedArray()), "META-INF/zbkt/glue/$feature", "txt")
      .use { it.write(luaListing(pkg, bridge, functions).toByteArray()) }
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

/** `Owner.function`, as messages name a function. */
internal fun label(function: KSFunctionDeclaration): String {
  val parent = function.parentDeclaration
  val simple = function.simpleName.asString()
  return if (parent == null) simple else "${parent.simpleName.asString()}.$simple"
}
