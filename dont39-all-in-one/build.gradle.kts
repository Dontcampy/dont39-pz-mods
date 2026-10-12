import groovy.json.JsonSlurper

plugins {
  id("buildsrc.convention.pz-java")
}

version = "0.1.0"

val modId = "Dont39AllInOne"
val features = listOf(":viewpoint-blood-fx-fix")

features.forEach { evaluationDependsOn(it) }

dependencies {
  features.forEach { implementation(project(it)) }
}

tasks.jar {
  archiveFileName = "$modId.jar"
  features.forEach { from(project(it).the<SourceSetContainer>()["main"].output) }
  duplicatesStrategy = DuplicatesStrategy.FAIL
  manifest {
    attributes("Implementation-Title" to modId, "Implementation-Version" to project.version)
  }
}

val assembleMod = tasks.register<Sync>("assembleMod") {
  group = "mod"
  description = "Assembles the $modId mod folder."
  val version = project.version.toString()
  into(layout.buildDirectory.dir("mod/$modId"))
  from("mod/42") { into("42") }
  from(tasks.jar) { into("42/media/java/client") }
  features.forEach { from(project(it).file("src/main/lua")) { into("42/media/lua") } }
  duplicatesStrategy = DuplicatesStrategy.FAIL
  // expand() would treat the backslashes in require=\ZombieBuddy as escapes
  filesMatching("**/mod.info") { filter { it.replace("@VERSION@", version) } }
  doLast { destinationDir.resolve("common").mkdirs() }
}

val pzDir = providers.gradleProperty("pzDir")
val pzUserDir =
  providers.gradleProperty("pzUserDir").orElse(providers.systemProperty("user.home").map { "$it/Zomboid" })
val workshopDir = pzUserDir.map { "$it/Workshop/$modId" }

val deployWorkshop = tasks.register<Copy>("deployWorkshop") {
  group = "mod"
  description = "Copies $modId into ~/Zomboid/Workshop for local testing and uploading."
  val staged = file(workshopDir.get())
  val modFolder = staged.resolve("Contents/mods/$modId")
  val repoWorkshopTxt = file("mod/workshop/workshop.txt")
  doFirst {
    // the uploader writes the Workshop id into the staged copy
    val stagedTxt = staged.resolve("workshop.txt")
    val stagedId = if (stagedTxt.exists()) stagedTxt.readLines().firstOrNull { it.startsWith("id=") } else null
    if (stagedId != null && repoWorkshopTxt.readLines().none { it == stagedId }) {
      throw GradleException("$stagedTxt has '$stagedId' but $repoWorkshopTxt does not; add it there first.")
    }
    modFolder.deleteRecursively()
  }
  into(staged)
  from("mod/workshop")
  from(assembleMod) { into("Contents/mods/$modId") }
}

// Starts the game the way ProjectZomboid64.exe would, with ZombieBuddy's exit_after_game_init, then checks console.txt.
// Needs Steam running; loads whichever mods are enabled in the game's mod list.
tasks.register("smokeTest") {
  group = "verification"
  description = "Launches Project Zomboid until ZombieBuddy has loaded $modId, then checks the log."
  dependsOn(deployWorkshop)
  val gameDir = file(pzDir.get())
  val console = file(pzUserDir.get()).resolve("console.txt")
  val version = project.version.toString()
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
    val expected = listOf(
      "[Dont39AllInOne] $version loaded",
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
  }
}
