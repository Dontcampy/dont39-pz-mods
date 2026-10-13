import buildsrc.convention.CheckBootstrapClasses
import buildsrc.convention.checkPlatformLog
import buildsrc.convention.runPzUntilInit
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
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

// Read by KotlinSwap; inside the mod jar, so ZombieBuddy's approval of the jar covers the checksum.
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
// server that enables only these mods, until ZombieBuddy's exit_after_game_init; then checks the server's log. Two
// launches: with the player's ZombieBuddy config, as players run it; with a test config that still has a preload entry
// for this mod from when it was one, which ZombieBuddy must drop without harm.
tasks.register("serverSmokeTest") {
  group = "verification"
  description = "Launches the dedicated server with $zabricId and $modId until init, also with a leftover preload entry, and checks the log."
  // the client's copies too: the player's config points at them, and they must be current as after a Workshop update
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
  val id = modId
  val zabricMod = zabricId
  val loaded = "[$id] ${project.version} loaded"
  val ready = "[$id] ready: $kotlinVersions"
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

    // configDir: ZombieBuddy's config_dir, null for the player's; returns the server's log
    fun launch(name: String, configDir: File?): Pair<String, File> {
      logger.lifecycle("serverSmokeTest: $name")
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
      ) {
        when {
          it == "-Dzomboid.steam=1" -> "-Dzomboid.steam=0"
          configDir != null && it.startsWith("-agentlib:zbNative") -> "$it,config_dir=${configDir.absolutePath}"
          else -> it
        }
      }
      if (!console.isFile) {
        throw GradleException("The server wrote no $console")
      }
      return console.readText() to console
    }

    fun expect(name: String, log: String, console: File, ordered: List<String>, absent: List<String> = emptyList()) {
      val missing = ordered.filterNot { it in log }
      if (missing.isNotEmpty()) {
        throw GradleException("$console ($name) is missing:\n" + missing.joinToString("\n") { "  $it" })
      }
      val present = absent.filter { it in log }
      if (present.isNotEmpty()) {
        throw GradleException("$console ($name) should not have:\n" + present.joinToString("\n") { "  $it" })
      }
      val positions = ordered.map { log.indexOf(it) }
      if (positions != positions.sorted()) {
        throw GradleException(
          "$console ($name) has these lines out of order:\n" + ordered.joinToString("\n") { "  $it" },
        )
      }
    }

    // Main installs Kotlin on every launch; this mod is no preload mod, so Zabric neither runs nor registers it
    fun expectReady(name: String, log: String, console: File) {
      checkPlatformLog(log, console, "serverSmokeTest")
      expect(
        name,
        log,
        console,
        listOf(loaded, ready),
        absent = listOf("[$zabricMod] preLaunch: $id", "[$zabricMod] restart the server once"),
      )
    }

    val player = "player's config"
    launch(player, null).let { (log, console) -> expectReady(player, log, console) }

    val configDir = smokeDir.resolve("zombie_buddy")
    configDir.deleteRecursively()
    configDir.mkdirs()
    fun preloadEntry(mod: String): Map<String, String> {
      val dir = cache.resolve("mods/$mod/42")
      val jar = dir.resolve("mod.info").readLines().first { it.startsWith("javaJarFile=") }.substringAfter('=').trim()
      return mapOf("infPath" to dir.resolve("mod.info").absolutePath, "jarPath" to dir.resolve(jar).absolutePath)
    }
    val config = configDir.resolve("config.json")
    config.writeText(
      JsonOutput.prettyPrint(
        JsonOutput.toJson(
          mapOf(
            "trusted_authors" to emptyList<String>(),
            "preload_mods" to mapOf(zabricMod to preloadEntry(zabricMod), id to preloadEntry(id)),
            "auto_fix_mod_order" to true,
            "fix_approval_dialog_cursor" to true,
          ),
        ),
      ),
    )

    val leftover = "leftover $id preload entry"
    launch(leftover, configDir).let { (log, console) -> expectReady(leftover, log, console) }
    // its jar has no ZB-Preload, so ZombieBuddy drops the entry at premain
    val preload = (JsonSlurper().parse(config) as Map<*, *>)["preload_mods"] as Map<*, *>
    if (id in preload || zabricMod !in preload) {
      throw GradleException("$config ($leftover): preload_mods should have $zabricMod and not $id: ${preload.keys}")
    }
  }
}
