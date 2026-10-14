package com.dont39.pz.zbktutils.processor

import com.google.devtools.ksp.KspExperimental
import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.getDeclaredProperties
import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.isConstructor
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.symbol.Visibility
import com.pzlauncher.zabric.processor.glue.HookDecls
import com.pzlauncher.zabric.processor.glue.HookDecls.Handler
import com.pzlauncher.zabric.processor.glue.HookDecls.HookClass
import com.pzlauncher.zabric.processor.glue.HookDecls.Kind
import com.pzlauncher.zabric.processor.glue.HookDecls.Member
import com.pzlauncher.zabric.processor.glue.HookDecls.Param
import com.pzlauncher.zabric.processor.glue.HookDecls.TargetClass
import com.pzlauncher.zabric.processor.glue.Messages
import com.pzlauncher.zabric.processor.glue.Names

internal val HANDLER_KINDS = mapOf(
  Names.INJECT to Kind.INJECT,
  Names.MODIFY_RETURN_VALUE to Kind.MODIFY_RETURN_VALUE,
  Names.MODIFY_VARIABLE to Kind.MODIFY_VARIABLE,
)
internal val PARAM_ANNOTATIONS = listOf(Names.SELF, Names.SHARE, Names.THROWN)
private val CALLBACK_INFOS = setOf(Names.CALLBACK_INFO, Names.CALLBACK_INFO_RETURNABLE)
private val INSTANCE_MODIFIERS = setOf(Modifier.PUBLIC, Modifier.JAVA_STATIC, Modifier.FINAL)

/**
 * Reads `@Hooks` objects into Zabric's [HookDecls], with the Kotlin rules on top of the shared ones: hook classes are
 * top-level objects called through `INSTANCE`, handlers are public (internal names are mangled) and take what the
 * target may pass as null as nullable. [facade] is the gate of hook classes that name none.
 */
@OptIn(KspExperimental::class)
internal class HookReader(
  private val resolver: Resolver,
  private val types: KspTypes,
  private val facade: () -> KSClassDeclaration?,
  private val error: (KSAnnotated?, String) -> Unit,
) {
  /** Gate classes and facade the glue names, for the generated files' dependencies. */
  val gates = mutableSetOf<KSClassDeclaration>()

  /** The declaration of a hook class, or null after reporting what keeps it from being one. */
  fun read(hooks: KSClassDeclaration): HookClass<JType>? {
    val name = hooks.qualifiedName?.asString() ?: hooks.simpleName.asString()
    var ok = true
    fun fail(node: KSAnnotated, message: String) {
      error(node, message)
      ok = false
    }
    if (hooks.classKind != ClassKind.OBJECT || hooks.isCompanionObject || hooks.parentDeclaration != null) {
      fail(hooks, "$name: in Kotlin, @Hooks is for top-level objects; the glue calls the handlers on its INSTANCE")
    }
    if (hooks.getVisibility().hidden()) {
      fail(hooks, "$name: a hook object must be public or internal: the glue calls its handlers from another package")
    }
    ok = checkMixin(hooks, name) && ok

    val annotation = hooks.annotation(Names.HOOKS)!!
    val targets = mutableListOf<TargetClass<JType>>()
    annotation.list("value").forEach { value ->
      val type = value as? KSType
      val declaration = type?.takeUnless { it.isError }?.let { types.classOf(it.declaration) }
      if (declaration == null) {
        fail(hooks, "$name: the target class $value cannot be resolved")
      } else {
        target(declaration)?.let { targets += it } ?: run { ok = false }
      }
    }
    annotation.list("targets").forEach { value ->
      val declaration = types.declaration(value as String)
      if (declaration == null) {
        fail(hooks, Messages.targetNotFound(name, value))
      } else {
        target(declaration)?.let { targets += it } ?: run { ok = false }
      }
    }
    val gate = gate(hooks, name, annotation.arg("gate") as? KSType)
    if (gate == null) ok = false

    val handlers = mutableListOf<Handler<JType>>()
    hooks.declarations.filterIsInstance<KSFunctionDeclaration>().forEach { function ->
      if (HANDLER_KINDS.keys.none { function.annotation(it) != null }) return@forEach
      handler(hooks, function)?.let { handlers += it } ?: run { ok = false }
    }
    if (!ok) return null
    return HookClass(
      hooks,
      name,
      types.binaryName(hooks),
      hooks.simpleName.asString(),
      "$name.INSTANCE",
      gate,
      targets,
      handlers,
    )
  }

  /** The named gate, or the facade when omitted; null after an error. */
  private fun gate(hooks: KSClassDeclaration, name: String, gate: KSType?): HookDecls.Gate? {
    val declaration = gate?.takeUnless { it.isError }?.let { types.classOf(it.declaration) }
    if (gate != null && declaration == null) {
      error(hooks, "$name: the gate class $gate cannot be resolved")
      return null
    }
    val chosen = if (declaration == null || declaration.qualifiedName?.asString() == Names.HOOK_GATE) {
      facade() ?: return null
    } else {
      val isGate = declaration.getAllSuperTypes().any { it.declaration.qualifiedName?.asString() == Names.HOOK_GATE }
      val instance = declaration.classKind == ClassKind.OBJECT && !declaration.isCompanionObject ||
        declaration.getDeclaredProperties().any {
          it.simpleName.asString() == "INSTANCE" &&
            resolver.effectiveJavaModifiers(it).containsAll(INSTANCE_MODIFIERS)
        }
      val public = generateSequence(declaration) { it.parentDeclaration as? KSClassDeclaration }
        .all { Modifier.PUBLIC in resolver.effectiveJavaModifiers(it) }
      if (!isGate || !instance || !public) {
        error(
          hooks,
          "$name: the gate ${declaration.qualifiedName?.asString()} must be a public HookGate with a public static " +
            "final INSTANCE of a HookGate type (or a Kotlin object)",
        )
        return null
      }
      declaration
    }
    gates += chosen
    return HookDecls.Gate(types.canonicalName(chosen), types.binaryName(chosen))
  }

  private fun handler(hooks: KSClassDeclaration, function: KSFunctionDeclaration): Handler<JType>? {
    val kinds = HANDLER_KINDS.keys.filter { function.annotation(it) != null }
    val jvmName = resolver.getJvmName(function)
    // internal names are mangled; the source name reads better in the error that rejects them
    val shownName = jvmName?.takeIf { function.getVisibility() == Visibility.PUBLIC } ?: function.simpleName.asString()
    val label = "${hooks.simpleName.asString()}.$shownName"
    if (kinds.size > 1) {
      error(function, Messages.severalKinds(label))
      return null
    }
    val signature = resolver.mapToJvmSignature(function)
    val problem = when {
      function.getVisibility() != Visibility.PUBLIC ->
        "a handler must be public (the generated Java glue calls it; internal names are mangled)"
      function.extensionReceiver != null -> "a handler cannot be an extension function"
      function.typeParameters.isNotEmpty() -> "a handler cannot have type parameters"
      Modifier.SUSPEND in function.modifiers -> "a handler cannot be a suspend function"
      function.parameters.any { it.isVararg } -> "a handler cannot take vararg parameters"
      jvmName == null || signature == null -> "cannot tell the handler's JVM name or signature"
      else -> null
    }
    if (problem != null) {
      error(function, "$label: $problem")
      return null
    }
    val (paramDescriptors, returnDescriptor) = parseMethodDescriptor(signature!!)
    if (paramDescriptors.size != function.parameters.size) {
      error(function, "$label: its JVM signature $signature does not match its ${function.parameters.size} parameters")
      return null
    }
    val annotationName = kinds.single()
    val kind = HANDLER_KINDS.getValue(annotationName)
    val annotation = function.annotation(annotationName)!!
    val at = annotation.list("at").map { (it as KSAnnotation).arg("value") as String }

    var ok = true
    val params = function.parameters.mapIndexed { i, parameter ->
      val type = parameter.type.resolve()
      val jType = types.fromDescriptor(paramDescriptors[i], type)
      val share = parameter.annotation(Names.SHARE)?.arg("value") as String?
      val self = parameter.annotation(Names.SELF) != null
      val thrown = parameter.annotation(Names.THROWN) != null
      val reference = jType.descriptor.length > 1
      val name = parameter.name?.asString() ?: "p$i"
      // a non-null Kotlin parameter checks for null on entry, which would throw inside the target
      val nullProblem = when {
        thrown && !type.isMarkedNullable -> "@Thrown must be nullable (Throwable?): it is null when the target returned"
        !self && share == null && !thrown && reference && jType.name !in CALLBACK_INFOS && !type.isMarkedNullable ->
          "a value the target passes must be nullable (${render(type)}?): the target may pass null"
        else -> null
      }
      if (nullProblem != null) {
        error(function, "$label: parameter $name: $nullProblem")
        ok = false
      }
      Param(name, jType, self, share, thrown)
    }
    if (!ok) return null
    return Handler(
      function,
      jvmName,
      kind,
      annotation.list("method").map { it as String },
      at,
      annotation.arg("cancellable") as? Boolean ?: false,
      annotation.arg("always") as? Boolean ?: false,
      kind != Kind.MODIFY_VARIABLE || annotation.arg("argsOnly") as? Boolean ?: false,
      annotation.arg("index") as? Int ?: -1,
      annotation.arg("ordinal") as? Int ?: -1,
      params,
      types.fromDescriptor(returnDescriptor),
    )
  }

  private fun checkMixin(hooks: KSClassDeclaration, name: String): Boolean {
    var ok = true
    fun check(node: KSAnnotated, where: String) {
      node.annotations.forEach {
        val annotation = it.qualifiedName()
        if (Messages.isMixin(annotation)) {
          error(node, Messages.unsupportedMixin(where, annotation))
          ok = false
        }
      }
    }
    check(hooks, name)
    hooks.declarations.forEach { member ->
      val memberName = member.simpleName.asString()
      check(member, "${hooks.simpleName.asString()}.$memberName")
      (member as? KSFunctionDeclaration)?.parameters?.forEach {
        check(it, "${hooks.simpleName.asString()}.$memberName parameter ${it.name?.asString()}")
      }
    }
    return ok
  }

  /**
   * Declared methods and constructors, plus the bridges the compiler adds for them, which KSP does not show. Null
   * after an error.
   */
  private fun target(declaration: KSClassDeclaration): TargetClass<JType>? {
    val members = mutableListOf<Member<JType>>()
    val descriptors = mutableSetOf<String>()
    val functions = declaration.declarations.filterIsInstance<KSFunctionDeclaration>().toList()
    val named = functions.map { function ->
      val signature = resolver.mapToJvmSignature(function)
      val name = if (function.isConstructor()) "<init>" else resolver.getJvmName(function)
      if (signature == null || name == null) {
        val owner = declaration.qualifiedName?.asString()
        error(null, "cannot tell the JVM name or signature of $owner.${function.simpleName.asString()}")
        return null
      }
      val modifiers = resolver.effectiveJavaModifiers(function) + function.modifiers
      val (params, returns) = parseMethodDescriptor(signature)
      val hasBody = modifiers.none { it == Modifier.ABSTRACT || it == Modifier.JAVA_NATIVE || it == Modifier.EXTERNAL }
      members += Member(
        name,
        params.map(types::fromDescriptor),
        types.fromDescriptor(returns),
        Modifier.JAVA_STATIC in modifiers,
        hasBody,
        false,
      )
      descriptors += name + signature
      Triple(function, name, modifiers)
    }
    val supertypes = declaration.getAllSuperTypes().mapNotNull { types.classOf(it.declaration) }.toList()
    named.forEach { (function, name, modifiers) ->
      if (function.isConstructor() || Modifier.JAVA_STATIC in modifiers || Modifier.PRIVATE in modifiers) return@forEach
      supertypes.forEach { supertype ->
        supertype.declarations.filterIsInstance<KSFunctionDeclaration>()
          .filter { it.simpleName == function.simpleName && !it.isConstructor() }
          .filter { resolver.overrides(function, it, declaration) }
          .forEach { overridden ->
            val signature = resolver.mapToJvmSignature(overridden) ?: return@forEach
            if (descriptors.add(name + signature)) {
              val (params, returns) = parseMethodDescriptor(signature)
              val paramTypes = params.map(types::fromDescriptor)
              members += Member(name, paramTypes, types.fromDescriptor(returns), false, true, true)
            }
          }
      }
    }
    val binary = types.binaryName(declaration)
    return TargetClass(binary, types.fromDescriptor("L${binary.replace('.', '/')};"), members)
  }
}

internal fun Visibility.hidden() =
  this == Visibility.PRIVATE || this == Visibility.PROTECTED || this == Visibility.LOCAL

internal fun KSAnnotation.qualifiedName(): String =
  annotationType.resolve().declaration.qualifiedName?.asString() ?: shortName.asString()

internal fun KSAnnotated.annotation(name: String): KSAnnotation? =
  annotations.firstOrNull { it.qualifiedName() == name }

internal fun KSAnnotation.arg(name: String): Any? {
  val argument = arguments.firstOrNull { it.name?.asString() == name }
    ?: defaultArguments.firstOrNull { it.name?.asString() == name }
  return argument?.value
}

/** An array attribute; a single value for an attribute that is not an array. */
internal fun KSAnnotation.list(name: String): List<Any?> = when (val value = arg(name)) {
  null -> emptyList()
  is List<*> -> value
  is Array<*> -> value.toList()
  else -> listOf(value)
}
