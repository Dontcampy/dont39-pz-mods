import groovy.json.JsonSlurper

plugins {
  id("buildsrc.convention.pz-kotlin")
  id("buildsrc.convention.pz-mod")
}

version = "0.2.0"

val modId = extra["modId"] as String
@Suppress("UNCHECKED_CAST")
val pzUserDir = extra["pzUserDir"] as Provider<String>
val features = listOf(":viewpoint-blood-fx-fix")
val kotlinLib = ":zombie-buddy-kotlin"

features.forEach { evaluationDependsOn(it) }
evaluationDependsOn(kotlinLib)

dependencies {
  features.forEach { implementation(project(it)) }
  // provided at runtime by the ZombieBuddyKotlin mod
  compileOnly(project(kotlinLib))
}

tasks.jar {
  features.forEach { from(project(it).the<SourceSetContainer>()["main"].output) }
  duplicatesStrategy = DuplicatesStrategy.FAIL
}

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
    val expected = ordered + listOf(
      "Found patch class: com.dont39.pz.allinone.ViewpointBloodFxFixPatches\$AimTarget",
      "Found patch class: com.dont39.pz.allinone.ViewpointBloodFxFixPatches\$BloodRender",
      "Found patch class: com.dont39.pz.allinone.ViewpointBloodFxFixPatches\$GoreRender",
      "Exposing global functions from class: com.dont39.pz.allinone.ViewpointBloodFxFixLua",
    )
    val feature =
      log.lines().lastOrNull { "viewpoint-blood-fx-fix: " in it }?.substringAfter("viewpoint-blood-fx-fix: ")
    logger.lifecycle("viewpoint-blood-fx-fix: ${feature ?: "no status line"}")
    val patched = if (feature?.startsWith("ready") == true) {
      listOf("bloodfx.Gpu", "bloodfx.GoreGpu", "bloodfx.Aim").map { "Transformed: $it" }
    } else {
      emptyList()
    }
    val missing = (expected + patched).filterNot { it in log }
    if (missing.isNotEmpty() || feature == null) {
      throw GradleException("$console is missing:\n" + missing.joinToString("\n") { "  $it" })
    }
    val positions = ordered.map { log.indexOf(it) }
    if (positions != positions.sorted()) {
      throw GradleException("$console has these lines out of order:\n" + ordered.joinToString("\n") { "  $it" })
    }
  }
}
