import buildsrc.convention.feature.PzArtifact
import buildsrc.convention.feature.jvmFolders
import buildsrc.convention.feature.pzIncoming
import buildsrc.convention.mod.kotlinVersions
import buildsrc.convention.mod.modInfoOf
import buildsrc.convention.mod.pzServerDir
import buildsrc.convention.mod.pzWorkshopContent
import buildsrc.convention.smoke.ServerSmokeTest
import java.security.MessageDigest
import java.util.HexFormat
import javax.inject.Inject

plugins {
  id("buildsrc.convention.pz-kotlin")
  id("buildsrc.convention.pz-mod")
  // the Java classes run before the swap, or without it
  id("buildsrc.convention.pz-bootstrap")
}

version = "0.8.0"

val pkgDir = "com/dont39/pz/zbkt"

// the runtime every Kotlin mod builds on, shipped in this jar
val utils = configurations.dependencyScope("utils")
// kotlinx, merged into the mod jar unrelocated
val bundledDeps = configurations.dependencyScope("bundledDeps")
// the unmodified kotlin-stdlib jar, shipped next to the mod jar and appended to the bootstrap search at runtime
val stdlibDeps = configurations.dependencyScope("stdlibDeps")
// the serverSmokeTest server loads it
val zabricMod = configurations.dependencyScope("zabricMod")

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
val utilsClasses = jvmFolders("utilsClasses", utils, LibraryElements.CLASSES)
val utilsResources = jvmFolders("utilsResources", utils, LibraryElements.RESOURCES)
val zabricModFolder = pzIncoming("zabricModFolder", zabricMod, PzArtifact.MOD_FOLDER)

dependencies {
  compileOnly(project(":zabric"))
  utils(project(":zb-kt-utils"))
  bundledDeps(libs.kotlinxCoroutines)
  bundledDeps(libs.kotlinxSerialization)
  stdlibDeps(libs.kotlinStdlib) { isTransitive = false }
  zabricMod(project(":zabric"))
}

val bundledJars = bundled.get().incoming.artifactView {
  componentFilter { it is ModuleComponentIdentifier && it.group == "org.jetbrains.kotlinx" }
}.files

interface Injected {
  @get:Inject
  val archives: ArchiveOperations
}

val archives = objects.newInstance<Injected>().archives

// Read by KotlinSwap; inside the mod jar, so ZombieBuddy's approval of the jar covers the checksum.
val runtimeProperties = tasks.register<WriteProperties>("runtimeProperties") {
  description = "Writes a properties file with the Kotlin runtime version and checksum, so KotlinSwap can verify it."
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

tasks.jar {
  manifest {
    attributes(
      // ZombieBuddy also needs javaPreload=true in mod.info
      "ZB-Preload" to "true",
      // makes Zabric keep the mod preloaded
      "Zabric-PreLaunch" to "${pzMod.javaPkgName}.PreLaunchEntry",
    )
  }
  from(utilsClasses, utilsResources)
  from(bundledJars.elements.map { jars -> jars.map { archives.zipTree(it) } }) {
    exclude("META-INF/MANIFEST.MF", "META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA", "META-INF/*.EC")
    exclude("module-info.class", "META-INF/versions/**/module-info.class")
  }
  duplicatesStrategy = DuplicatesStrategy.FAIL
}

tasks.named<Sync>("assembleMod") {
  from(stdlib) { into("42/media/java/lib") }
}

// ---- serverSmokeTest ----

val modId = pzMod.modId
val zabricModId = modInfoOf("zabric").id
val serverSmokeDir = layout.buildDirectory.dir("serverSmoke")
// the server's -cachedir: its own copies of the mods, so Zabric must keep the client's byte-identical preload entries
val serverCache = serverSmokeDir.map { it.dir("Zomboid") }
// a mod loads only with the mods it requires, and the server runs without Steam, so not from the Workshop
val zombieBuddyMod = pzWorkshopContent().map { it.dir("3619862853/mods/ZombieBuddy") }

val stageServerMods = tasks.register<Sync>("stageServerMods") {
  description = "Copies the mods the serverSmokeTest server loads into its cache dir."
  val zombieBuddy = zombieBuddyMod
  into(serverCache.map { it.dir("mods") })
  from(zabricModFolder) { into(zabricModId) }
  from(tasks.named("assembleMod")) { into(modId) }
  from(zombieBuddy) { into("ZombieBuddy") }
  doFirst {
    val dir = zombieBuddy.get().asFile
    if (!dir.resolve("42/mod.info").isFile) {
      throw GradleException("No ZombieBuddy mod at $dir: subscribe to it in Steam and let it download.")
    }
  }
}

tasks.register<ServerSmokeTest>("serverSmokeTest") {
  description = "Launches the dedicated server with $zabricModId and ${pzMod.modId} until init, before and after " +
    "${pzMod.modId}'s preload registration and with the player's config, and checks the log."
  // the client's copies too: the player's config points at them, and they must be current as after a Workshop update
  dependsOn(stageServerMods, tasks.named("deployWorkshop"), ":zabric:deployWorkshop")
  serverDir = pzServerDir()
  smokeDir = serverSmokeDir
  cacheDir = serverCache
  serverName = "zbktsmoke"
  modId = pzMod.modId
  zabricId = zabricModId
  version = project.version.toString()
  kotlinVersions = kotlinVersions()
}
