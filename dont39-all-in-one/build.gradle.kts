import groovy.json.JsonSlurper

plugins {
  id("buildsrc.convention.pz-kotlin")
  id("buildsrc.convention.pz-mod")
}

version = "0.3.0"

val modId = extra["modId"] as String
@Suppress("UNCHECKED_CAST")
val pzUserDir = extra["pzUserDir"] as Provider<String>
val features = listOf(":viewpoint-blood-fx-fix", ":mod-options-sidebar")
val kotlinLib = ":zombie-buddy-kotlin"
// code shared by the features, bundled like a feature's classes; no Lua, no smokeTest expectations
val internalLibs = listOf(":zombiebuddy-kotlin-utils")

features.forEach { evaluationDependsOn(it) }
internalLibs.forEach { evaluationDependsOn(it) }
evaluationDependsOn(kotlinLib)

dependencies {
  features.forEach { implementation(project(it)) }
  internalLibs.forEach { implementation(project(it)) }
}

// What each feature's generated glue contains (written by zombiebuddy-kotlin-utils-processor, see GlueProcessor.kt), by
// feature module name. Features without generated glue have no files there.
val glueDirs = features.associate {
  val resources = project(it).the<SourceSetContainer>()["main"].output.resourcesDir!!
  project(it).name to resources.resolve("META-INF/dont39/glue")
}

tasks.jar {
  features.forEach { from(project(it).the<SourceSetContainer>()["main"].output) }
  internalLibs.forEach { from(project(it).the<SourceSetContainer>()["main"].output) }
  // build data for checkGlue and smokeTest, not needed in game
  exclude("META-INF/dont39/**")
  duplicatesStrategy = DuplicatesStrategy.FAIL
}

// Each feature's processor checks its own Lua names; Lua globals are shared, so they must also differ across features.
val checkGlue = tasks.register("checkGlue") {
  group = "verification"
  description = "Checks that no two features generate the same global Lua function."
  dependsOn(features.map { "$it:processResources" })
  val dirs = glueDirs
  doLast {
    val owners = dirs.flatMap { (feature, dir) ->
      dir.listFiles { file -> file.extension == "txt" }.orEmpty().flatMap { it.readLines() }
        .filter { it.startsWith("luaGlobal ") }.map { it.substringAfter(' ') to feature }
    }.groupBy({ it.first }, { it.second })
    val clashes = owners.filterValues { it.size > 1 }
    if (clashes.isNotEmpty()) {
      throw GradleException(
        "Global Lua functions declared by more than one feature:\n" +
          clashes.entries.joinToString("\n") { (name, by) -> "  $name: ${by.joinToString()}" },
      )
    }
  }
}
tasks.named("check") { dependsOn(checkGlue) }

tasks.named<Sync>("assembleMod") {
  from(tasks.jar) { into("42/media/java/client") }
  features.forEach { from(project(it).file("src/main/lua")) { into("42/media/lua") } }
}

val pzDir = providers.gradleProperty("pzDir")

// Starts the game the way ProjectZomboid64.exe would, with ZombieBuddy's exit_after_game_init, then checks console.txt.
// Needs Steam running; loads whichever mods are enabled in the game's mod list.
tasks.register("smokeTest") {
  group = "verification"
  description = "Launches Project Zomboid until ZombieBuddy has loaded $modId, then checks the log."
  dependsOn(tasks.named("deployWorkshop"), "$kotlinLib:deployWorkshop")
  val gameDir = file(pzDir.get())
  val console = file(pzUserDir.get()).resolve("console.txt")
  val version = project.version.toString()
  val kotlinLibVersion = project(kotlinLib).version.toString()
  val glue = glueDirs
  val logPrefix = "[$modId] "
  val kotlinVersions = libs.versions.let {
    "Kotlin ${it.kotlin.get()}, kotlinx-coroutines ${it.kotlinxCoroutines.get()}, " +
      "kotlinx-serialization ${it.kotlinxSerializationJSON.get()}"
  }
  doLast {
    val launcher = JsonSlurper().parse(gameDir.resolve("ProjectZomboid64.json")) as Map<*, *>
    val vmArgs = (launcher["vmArgs"] as List<*>).map { it.toString() }.map {
      if (it.startsWith("-agentlib:zbNative")) "$it,exit_after_game_init,verbosity=1" else it
    }
    if (vmArgs.none { it.startsWith("-agentlib:zbNative") }) {
      throw GradleException("ProjectZomboid64.json has no -agentlib:zbNative argument; is ZombieBuddy installed?")
    }
    val classpath = (launcher["classpath"] as List<*>).joinToString(File.pathSeparator)
    val command = listOf(gameDir.resolve("jre64/bin/java.exe").path) + vmArgs +
      listOf("-cp", classpath, (launcher["mainClass"] as String).replace('/', '.'))
    // the game's output also goes to console.txt, which is what gets checked
    val game = ProcessBuilder(command).directory(gameDir).redirectOutput(ProcessBuilder.Redirect.DISCARD)
      .redirectError(ProcessBuilder.Redirect.DISCARD).start()
    if (!game.waitFor(10, TimeUnit.MINUTES)) {
      game.destroyForcibly()
      throw GradleException("The game did not exit within 10 minutes")
    }

    val log = console.readText()
    // in this order: the library installs Kotlin before All-in-One's Kotlin Main runs
    val ordered = listOf(
      "[ZombieBuddyKotlin] $kotlinLibVersion loaded",
      "[ZombieBuddyKotlin] ready: $kotlinVersions",
      "[Dont39AllInOne] $version loaded",
    )
    // Features with generated glue: a status line, each patch class and Lua bridge, and the targets once ready.
    val disabled = mutableListOf<String>()
    val generated = glue.flatMap { (name, dir) ->
      val lines = dir.listFiles { file -> file.extension == "txt" }.orEmpty().flatMap { it.readLines() }
      if (lines.isEmpty()) return@flatMap emptyList()
      val statusPrefix = "$logPrefix$name: "
      val status = log.lines().lastOrNull { statusPrefix in it }?.substringAfter(statusPrefix)
      logger.lifecycle("$name: ${status ?: "no status line"}")
      // idle is fine (a target mod is not enabled); disabled means init() itself failed
      if (status?.startsWith("disabled") == true) disabled += "$statusPrefix$status"
      listOf(statusPrefix) + lines.mapNotNull { line ->
        val value = line.substringAfter(' ')
        when (line.substringBefore(' ')) {
          "patch" -> "Found patch class: $value"
          "lua" -> "Exposing global functions from class: $value"
          "target" -> if (status?.startsWith("ready") == true) "Transformed: $value" else null
          else -> null
        }
      }
    }
    // Lua-only features log "ready" when their client Lua loads, which GameWindow.init does before ZombieBuddy exits.
    val luaOnly = listOf("${logPrefix}mod-options-sidebar: ready")
    val missing = (ordered + generated + luaOnly).filterNot { it in log }
    if (missing.isNotEmpty()) {
      throw GradleException("$console is missing:\n" + missing.joinToString("\n") { "  $it" })
    }
    if (disabled.isNotEmpty()) {
      throw GradleException(
        "$console has features that disabled themselves:\n" + disabled.joinToString("\n") { "  $it" },
      )
    }
    val positions = ordered.map { log.indexOf(it) }
    if (positions != positions.sorted()) {
      throw GradleException("$console has these lines out of order:\n" + ordered.joinToString("\n") { "  $it" })
    }
  }
}
