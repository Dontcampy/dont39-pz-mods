import java.io.DataInputStream
import java.security.MessageDigest
import java.util.HexFormat
import javax.inject.Inject

plugins {
  id("buildsrc.convention.pz-kotlin")
  id("buildsrc.convention.pz-mod")
}

version = "0.1.0"

val pkgDir = "com/dont39/pz/zombiebuddykotlin"

// kotlinx, merged into the mod jar unrelocated
val bundledDeps = configurations.dependencyScope("bundledDeps")
// the unmodified kotlin-stdlib jar, shipped next to the mod jar and appended to the bootstrap search at runtime
val stdlibDeps = configurations.dependencyScope("stdlibDeps")

// resolve the JVM variants of the multiplatform kotlin/kotlinx modules
fun jvmRuntime(name: String, deps: NamedDomainObjectProvider<out Configuration>) = configurations.resolvable(name) {
  extendsFrom(deps.get())
  attributes {
    attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
    attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
    attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
  }
}

val bundled = jvmRuntime("bundled", bundledDeps)
val stdlib = jvmRuntime("stdlib", stdlibDeps)

dependencies {
  bundledDeps(libs.kotlinxCoroutines)
  bundledDeps(libs.kotlinxSerialization)
  stdlibDeps(libs.kotlinStdlib) { isTransitive = false }
}

val bundledJars = bundled.get().incoming.artifactView {
  componentFilter { it is ModuleComponentIdentifier && it.group == "org.jetbrains.kotlinx" }
}.files

interface Injected {
  @get:Inject
  val archives: ArchiveOperations
}

val archives = objects.newInstance<Injected>().archives

// Read by ZombieBuddyKotlin.install(); inside the mod jar, so ZombieBuddy's approval of the jar covers the checksum.
val runtimeProperties = tasks.register<WriteProperties>("runtimeProperties") {
  destinationFile = layout.buildDirectory.file("generated/runtime/$pkgDir/stdlib.properties")
  property("file", stdlib.get().elements.map { it.single().asFile.name })
  property("version", libs.versions.kotlin)
  property("sha256", stdlib.get().elements.map {
    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(it.single().asFile.readBytes()))
  })
  property("coroutines", libs.versions.kotlinxCoroutines)
  property("serialization", libs.versions.kotlinxSerializationJSON)
}

tasks.processResources {
  from(runtimeProperties) { into(pkgDir) }
}

// Main and ZombieBuddyKotlin run before the Kotlin runtime is installed, so the Java classes must not reference kotlin.*
val checkBootstrapClasses = tasks.register("checkBootstrapClasses") {
  description = "Fails if a Java class of this module references a kotlin/ type."
  val classes = tasks.compileJava.flatMap { it.destinationDirectory }
  inputs.files(classes)
  doLast {
    // Constant-pool strings naming a kotlin/ class: class entries ("kotlin/...") and descriptors ("...Lkotlin/...").
    // A raw byte search would also match this module's own package, com/dont39/pz/zombiebuddykotlin/.
    fun kotlinRefs(bytes: ByteArray): List<String> {
      val input = DataInputStream(bytes.inputStream())
      input.skipBytes(8) // magic, minor, major
      val count = input.readUnsignedShort()
      val refs = mutableListOf<String>()
      var i = 1
      while (i < count) {
        when (val tag = input.readUnsignedByte()) {
          1 -> {
            val utf = input.readUTF()
            if (utf.startsWith("kotlin/") || "Lkotlin/" in utf) refs += utf
          }
          7, 8, 16, 19, 20 -> input.skipBytes(2)
          15 -> input.skipBytes(3)
          3, 4, 9, 10, 11, 12, 17, 18 -> input.skipBytes(4)
          5, 6 -> {
            input.skipBytes(8)
            i++ // takes two slots
          }
          else -> throw GradleException("unknown constant pool tag $tag")
        }
        i++
      }
      return refs
    }

    val offenders = classes.get().asFileTree.matching { include("**/*.class") }.files.mapNotNull { file ->
      kotlinRefs(file.readBytes()).takeIf { it.isNotEmpty() }?.let { "${file.name}: ${it.joinToString()}" }
    }
    if (offenders.isNotEmpty()) {
      throw GradleException("Java bootstrap classes reference Kotlin types:\n" + offenders.joinToString("\n"))
    }
  }
}

tasks.check { dependsOn(checkBootstrapClasses) }

tasks.jar {
  dependsOn(checkBootstrapClasses)
  from(bundledJars.elements.map { jars -> jars.map { archives.zipTree(it) } }) {
    exclude("META-INF/MANIFEST.MF", "META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA", "META-INF/*.EC")
    exclude("module-info.class", "META-INF/versions/**/module-info.class")
  }
  duplicatesStrategy = DuplicatesStrategy.FAIL
}

tasks.named<Sync>("assembleMod") {
  // no client/ or server/ in the path: loads on both (ZombieBuddy ModdingGuide, "Client/server JAR paths")
  from(tasks.jar) { into("42/media/java") }
  from(stdlib) { into("42/media/java/lib") }
}
