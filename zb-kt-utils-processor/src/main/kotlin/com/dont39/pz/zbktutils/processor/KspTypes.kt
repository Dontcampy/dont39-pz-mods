package com.dont39.pz.zbktutils.processor

import com.google.devtools.ksp.KspExperimental
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeAlias
import com.google.devtools.ksp.symbol.KSTypeArgument
import com.google.devtools.ksp.symbol.KSTypeParameter
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.symbol.Variance
import com.pzlauncher.zabric.processor.glue.TypeSystem

/**
 * A Java type as the generated glue sees it. [descriptor]: the JVM descriptor of the erasure; [name]: its canonical
 * name (`int`, `a.b.Outer.Inner[]`); [wildcard]: `?`, `? extends ` or `? super ` for a type argument.
 */
internal data class JType(
  val descriptor: String,
  val name: String,
  val args: List<JType> = emptyList(),
  val wildcard: String? = null,
)

private const val OBJECT = "Ljava/lang/Object;"

private val PRIMITIVE_NAMES = mapOf(
  'Z' to "boolean", 'B' to "byte", 'C' to "char", 'S' to "short", 'I' to "int", 'J' to "long", 'F' to "float",
  'D' to "double", 'V' to "void",
)

/** Kotlin types that compile to JVM primitives: their descriptor and box. */
private val KOTLIN_PRIMITIVES = mapOf(
  "kotlin.Boolean" to ("Z" to "java/lang/Boolean"),
  "kotlin.Byte" to ("B" to "java/lang/Byte"),
  "kotlin.Char" to ("C" to "java/lang/Character"),
  "kotlin.Short" to ("S" to "java/lang/Short"),
  "kotlin.Int" to ("I" to "java/lang/Integer"),
  "kotlin.Long" to ("J" to "java/lang/Long"),
  "kotlin.Float" to ("F" to "java/lang/Float"),
  "kotlin.Double" to ("D" to "java/lang/Double"),
)
private val KOTLIN_PRIMITIVE_ARRAYS = KOTLIN_PRIMITIVES.entries.associate { (name, jvm) -> "${name}Array" to jvm.first }

/**
 * Zabric's [TypeSystem] on KSP. Erasures come from JVM descriptors where KSP has them (the declarations' JVM
 * signatures), so `Int` and `Int?` map as the Kotlin compiler maps them; type arguments come from the Kotlin types.
 */
@OptIn(KspExperimental::class)
internal class KspTypes(private val resolver: Resolver) : TypeSystem<JType> {
  private val supertypes = HashMap<String, Set<String>>()
  private val declarations = HashMap<String, KSClassDeclaration?>()

  override fun erasedName(type: JType) = type.name

  override fun descriptor(type: JType) = type.descriptor

  override fun source(type: JType): String {
    if (type.wildcard == "?") return "?"
    val base = if (type.args.isEmpty()) {
      type.name
    } else {
      type.args.joinToString(", ", "${type.name}<", ">", transform = ::source)
    }
    return (type.wildcard ?: "") + base
  }

  override fun typeArguments(type: JType) = type.args

  override fun component(type: JType) =
    if (type.descriptor.startsWith("[")) fromDescriptor(type.descriptor.substring(1)) else null

  override fun isSubtype(sub: JType, sup: JType): Boolean {
    val a = sub.descriptor
    val b = sup.descriptor
    return when {
      a == b -> true
      // primitives (and void) only to themselves
      a.length == 1 || b.length == 1 -> false
      b == OBJECT -> true
      a.startsWith("[") && b.startsWith("[") ->
        isSubtype(fromDescriptor(a.substring(1)), fromDescriptor(b.substring(1)))
      a.startsWith("[") -> b == "Ljava/lang/Cloneable;" || b == "Ljava/io/Serializable;"
      b.startsWith("[") -> false
      else -> binary(b) in supertypesOf(binary(a))
    }
  }

  override fun accessProblem(type: JType, pkg: String): String? {
    val element = type.descriptor.trimStart('[')
    if (!element.startsWith("L")) return null
    var declaration: KSDeclaration? =
      declaration(binary(element)) ?: return "${type.name} is not on the compile classpath"
    while (declaration is KSClassDeclaration) {
      val modifiers = resolver.effectiveJavaModifiers(declaration)
      if (Modifier.PUBLIC !in modifiers &&
        (Modifier.PRIVATE in modifiers || declaration.packageName.asString() != pkg)
      ) {
        return "${canonicalName(declaration)} is not public"
      }
      declaration = declaration.parentDeclaration
    }
    return null
  }

  /** A field descriptor, without type arguments. */
  fun fromDescriptor(descriptor: String): JType = JType(descriptor, nameOf(descriptor))

  /** A JVM-signature [descriptor] with the type arguments of the Kotlin [type] it was compiled from. */
  fun fromDescriptor(descriptor: String, type: KSType): JType {
    val args = if (descriptor.startsWith("L")) expand(type).arguments.map(::argument) else emptyList()
    return fromDescriptor(descriptor).copy(args = args)
  }

  /** A Kotlin type as Java sees it; [boxed] for a position that takes no primitive, such as a type argument. */
  fun fromKotlin(type: KSType, boxed: Boolean = type.isMarkedNullable): JType {
    val expanded = expand(type)
    val nullable = boxed || type.isMarkedNullable || expanded.isMarkedNullable
    val declaration = expanded.declaration
    if (declaration is KSTypeParameter) {
      return declaration.bounds.firstOrNull()?.let { fromKotlin(it.resolve(), true) } ?: fromDescriptor(OBJECT)
    }
    val name = declaration.qualifiedName?.asString() ?: return fromDescriptor(OBJECT)
    KOTLIN_PRIMITIVES[name]?.let { (primitive, box) -> return fromDescriptor(if (nullable) "L$box;" else primitive) }
    KOTLIN_PRIMITIVE_ARRAYS[name]?.let { return fromDescriptor("[$it") }
    if (name == "kotlin.Array") {
      val element = expanded.arguments.singleOrNull()?.type?.resolve()?.let { fromKotlin(it, true) }
      return fromDescriptor("[" + (element?.descriptor ?: OBJECT))
    }
    if (name == "kotlin.Nothing") return fromDescriptor("Ljava/lang/Void;")
    val classDeclaration = declaration as? KSClassDeclaration ?: return fromDescriptor(OBJECT)
    val binary = binaryName(classDeclaration)
    return JType("L${binary.replace('.', '/')};", canonicalName(classDeclaration), expanded.arguments.map(::argument))
  }

  private fun argument(argument: KSTypeArgument): JType {
    val type = argument.type?.resolve()
    return when (argument.variance) {
      Variance.STAR -> fromDescriptor(OBJECT).copy(wildcard = "?")
      Variance.COVARIANT -> fromKotlin(type!!, true).copy(wildcard = "? extends ")
      Variance.CONTRAVARIANT -> fromKotlin(type!!, true).copy(wildcard = "? super ")
      Variance.INVARIANT -> fromKotlin(type!!, true)
    }
  }

  private fun expand(type: KSType): KSType {
    val declaration = type.declaration
    return if (declaration is KSTypeAlias) {
      val expanded = expand(declaration.type.resolve())
      if (type.isMarkedNullable) expanded.makeNullable() else expanded
    } else {
      type
    }
  }

  /** The JVM binary name, e.g. `a.b.Outer$Inner`; Kotlin's mapped types by their Java class. */
  fun binaryName(declaration: KSClassDeclaration): String {
    javaName(declaration)?.let { return javaBinaryName(it) }
    val nesting = generateSequence<KSDeclaration>(declaration) { it.parentDeclaration }
      .map { it.simpleName.asString() }.toList().asReversed().joinToString("$")
    val pkg = declaration.packageName.asString()
    return if (pkg.isEmpty()) nesting else "$pkg.$nesting"
  }

  fun canonicalName(declaration: KSClassDeclaration): String =
    javaName(declaration) ?: declaration.qualifiedName?.asString() ?: binaryName(declaration).replace('$', '.')

  /** A class by binary name; also finds Java classes that Kotlin maps to its own types. */
  fun declaration(binary: String): KSClassDeclaration? = declarations.getOrPut(binary) {
    listOf(binary.replace('$', '.'), binary).distinct().flatMap { name ->
      val ksName = resolver.getKSNameFromString(name)
      listOfNotNull(resolver.getClassDeclarationByName(ksName), resolver.mapJavaNameToKotlin(ksName)?.let {
        resolver.getClassDeclarationByName(it)
      })
    }.firstOrNull { binaryName(it) == binary }
  }

  fun classOf(declaration: KSDeclaration): KSClassDeclaration? = when (declaration) {
    is KSClassDeclaration -> declaration
    is KSTypeAlias -> classOf(expand(declaration.type.resolve()).declaration)
    else -> null
  }

  private fun javaName(declaration: KSClassDeclaration): String? {
    val name = declaration.qualifiedName ?: return null
    return resolver.mapKotlinNameToJava(name)?.asString()
  }

  private fun supertypesOf(binary: String): Set<String> = supertypes.getOrPut(binary) {
    val all = linkedSetOf<String>()
    val todo = ArrayDeque(listOfNotNull(declaration(binary)))
    while (todo.isNotEmpty()) {
      todo.removeFirst().superTypes.forEach { reference ->
        val declaration = classOf(reference.resolve().declaration) ?: return@forEach
        if (all.add(binaryName(declaration))) todo += declaration
      }
    }
    all
  }

  private fun nameOf(descriptor: String): String = when {
    descriptor.length == 1 -> PRIMITIVE_NAMES[descriptor[0]] ?: descriptor
    descriptor.startsWith("[") -> nameOf(descriptor.substring(1)) + "[]"
    else -> binary(descriptor).let { binary -> declaration(binary)?.let(::canonicalName) ?: binary.replace('$', '.') }
  }

  private fun binary(descriptor: String) = descriptor.substring(1, descriptor.length - 1).replace('/', '.')
}

/** A Java canonical name as a binary name: `java.util.Map.Entry` -> `java.util.Map$Entry`. */
private fun javaBinaryName(canonical: String): String {
  val parts = canonical.split('.')
  val firstClass = parts.indexOfFirst { it.first().isUpperCase() }.takeIf { it >= 0 } ?: return canonical
  return (parts.subList(0, firstClass) + parts.subList(firstClass, parts.size).joinToString("$")).joinToString(".")
}

/** The parameter descriptors and the return descriptor of a JVM method descriptor. */
internal fun parseMethodDescriptor(descriptor: String): Pair<List<String>, String> {
  require(descriptor.startsWith("(")) { "not a method descriptor: $descriptor" }
  val params = mutableListOf<String>()
  var i = 1
  while (descriptor[i] != ')') {
    val start = i
    while (descriptor[i] == '[') i++
    i = if (descriptor[i] == 'L') descriptor.indexOf(';', i) + 1 else i + 1
    params += descriptor.substring(start, i)
  }
  return params to descriptor.substring(i + 1)
}
