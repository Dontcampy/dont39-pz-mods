import buildsrc.convention.CheckBootstrapClasses
import buildsrc.convention.checkPlatformLog
import buildsrc.convention.runPzUntilInit
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID
import javax.inject.Inject

plugins {
  id("buildsrc.convention.pz-kotlin")
  id("buildsrc.convention.pz-mod")
}

version = "0.4.0"

// the runtime every Kotlin mod builds on, shipped in this jar
val utils = ":zb-kt-utils"
evaluationDependsOn(utils)

val pkgDir = "com/dont39/pz/zbkt"

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
  compileOnly(project(":zabric"))
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

// Read by PreMain; inside the mod jar, so ZombieBuddy's approval of the jar covers the checksum.
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

// The Java classes run before the swap, or without it, so they must not reference kotlin.*
val checkBootstrapClasses = tasks.register<CheckBootstrapClasses>("checkBootstrapClasses") {
  description = "Fails if a Java class of this module references a kotlin/ type."
  classes.from(tasks.compileJava.flatMap { it.destinationDirectory })
}

tasks.check { dependsOn(checkBootstrapClasses) }

tasks.jar {
  dependsOn(checkBootstrapClasses)
  // ZombieBuddy also needs javaPreload=true in mod.info, so a mod.info edit alone cannot make a jar preload
  manifest { attributes("ZB-Preload" to "true") }
  from(project(utils).the<SourceSetContainer>()["main"].output)
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

// ---- serverSmokeTest ----

val zabric = ":zabric"
evaluationDependsOn(zabric)
val modId = extra["modId"] as String
val zabricId = project(zabric).extra["modId"] as String
val pzDir = providers.gradleProperty("pzDir")
val pzServerDir = providers.gradleProperty("pzServerDir")
val serverName = "zbktsmoke"
val serverSmokeDir = layout.buildDirectory.dir("serverSmoke")
// the server's -cachedir: its own copies of the mods, so Zabric must keep the client's byte-identical preload entries
val serverCache = serverSmokeDir.map { it.dir("Zomboid") }
// a mod loads only with the mods it requires, and the server runs without Steam, so not from the Workshop
val zombieBuddyMod = pzDir.map { file("$it/../../workshop/content/108600/3619862853/mods/ZombieBuddy").normalize() }

val stageServerMods = tasks.register<Sync>("stageServerMods") {
  description = "Copies the mods the serverSmokeTest server loads into its cache dir."
  val zombieBuddy = zombieBuddyMod.get()
  into(serverCache.map { it.dir("mods") })
  from(project(zabric).tasks.named("assembleMod")) { into(zabricId) }
  from(tasks.named("assembleMod")) { into(modId) }
  from(zombieBuddy) { into("ZombieBuddy") }
  doFirst {
    if (!zombieBuddy.resolve("42/mod.info").isFile) {
      throw GradleException("No ZombieBuddy mod at $zombieBuddy: subscribe to it in Steam and let it download.")
    }
  }
}

// Starts the dedicated server the way its ProjectZomboid64.json does, without Steam, with its own cache dir and a test
// server that enables only these mods, until ZombieBuddy's exit_after_game_init; then checks the server's log.
tasks.register("serverSmokeTest") {
  group = "verification"
  description = "Launches the dedicated server with $zabricId and $modId until init, then checks the log."
  // the client's copies too, so the shared preload entries can stay as the client registered them
  dependsOn(stageServerMods, tasks.named("deployWorkshop"), "$zabric:deployWorkshop")
  val serverDir = file(pzServerDir.get())
  val smokeDir = serverSmokeDir.get().asFile
  val cache = serverCache.get().asFile
  val kotlinVersions = libs.versions.let {
    "Kotlin ${it.kotlin.get()}, kotlinx-coroutines ${it.kotlinxCoroutines.get()}, " +
      "kotlinx-serialization ${it.kotlinxSerializationJSON.get()}"
  }
  val mods = listOf("ZombieBuddy", zabricId, modId)
  val server = serverName
  doLast {
    // the server asks for one on a new server name and would wait for console input
    val passwordFile = smokeDir.resolve("adminpassword.txt")
    if (!passwordFile.isFile) {
      passwordFile.writeText(UUID.randomUUID().toString())
    }
    // the server fills in defaults for every other key
    cache.resolve("Server").mkdirs()
    cache.resolve("Server/$server.ini").writeText(
      "Mods=${mods.joinToString(";")}\nWorkshopItems=\nMap=Muldraugh, KY\nPublic=false\nUPnP=false\n",
    )
    val console = cache.resolve("server-console.txt")
    console.delete()
    runPzUntilInit(
      serverDir,
      "dedicated server",
      programArgs = listOf(
        "-cachedir=${cache.absolutePath}",
        "-servername",
        server,
        "-adminpassword",
        passwordFile.readText().trim(),
        "-nosteam",
      ),
      osVmArgs = true,
    ) { if (it == "-Dzomboid.steam=1") "-Dzomboid.steam=0" else it }

    if (!console.isFile) {
      throw GradleException("The server wrote no $console")
    }
    val log = console.readText()
    checkPlatformLog(log, console, "serverSmokeTest")
    val ready = "[ZombieBuddyKotlin] ready: $kotlinVersions"
    if (ready !in log) {
      throw GradleException("$console is missing:\n  $ready")
    }
  }
}
