import buildsrc.convention.MergeClassTweakers
import buildsrc.convention.PzGlue
import buildsrc.convention.WidenJars
import buildsrc.convention.checkPlatformLog
import buildsrc.convention.runPzUntilInit

plugins {
  id("buildsrc.convention.pz-kotlin")
  id("buildsrc.convention.pz-mod")
}

version = "0.5.0"

val modId = extra["modId"] as String
@Suppress("UNCHECKED_CAST")
val pzUserDir = extra["pzUserDir"] as Provider<String>
val features = listOf(":viewpoint-blood-fx-fix", ":mod-options-sidebar", ":ime-keyboard-fix")
// ZombieBuddyKotlin, which also ships zb-kt-utils
val kotlinLib = ":zb-kt"
// makes what the features' .classtweaker lists name public at game start
val zabric = ":zabric"

features.forEach { evaluationDependsOn(it) }
evaluationDependsOn(kotlinLib)
evaluationDependsOn(zabric)
val zabricId = project(zabric).extra["modId"] as String

dependencies {
  features.forEach { implementation(project(it)) }
  compileOnly(project(":zb-kt-utils"))
  compileOnly(project(kotlinLib))
}

val javaPkgName = file("mod/42/mod.info").readLines().firstOrNull { it.startsWith("javaPkgName=") }
  ?.substringAfter('=')?.trim() ?: throw GradleException("mod/42/mod.info has no javaPkgName= line")
features.forEach {
  project(it).extensions.findByType<PzGlue>()?.apply {
    gluePackage = javaPkgName
    luaPrefix = "${modId}_"
  }
}

// each feature's generated glue listing, by feature module name
val glueDirs = features.associate {
  val resources = project(it).the<SourceSetContainer>()["main"].output.resourcesDir!!
  project(it).name to resources.resolve("META-INF/zbkt/glue")
}

val mergeClassTweakers = tasks.register<MergeClassTweakers>("mergeClassTweakers") {
  lists.from(features.flatMap { project(it).tasks.withType<WidenJars>().map { task -> task.expanded } })
  merged = layout.buildDirectory.file("classtweaker/$modId.classtweaker")
}

tasks.jar {
  features.forEach { from(project(it).the<SourceSetContainer>()["main"].output) }
  // build data only (the features' source .classtweaker lists)
  exclude("META-INF/dont39/**")
  // where Zabric reads it at game start
  from(mergeClassTweakers) { into("META-INF/zabric") }
  duplicatesStrategy = DuplicatesStrategy.FAIL
}

// Lua globals are shared, so names must differ across features too
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
  dependsOn(tasks.named("deployWorkshop"), "$kotlinLib:deployWorkshop", "$zabric:deployWorkshop")
  val gameDir = file(pzDir.get())
  val console = file(pzUserDir.get()).resolve("console.txt")
  val version = project.version.toString()
  val kotlinLibVersion = project(kotlinLib).version.toString()
  val glue = glueDirs
  val logPrefix = "[$modId] "
  val zabricMod = zabricId
  val kotlinVersions = libs.versions.let {
    "Kotlin ${it.kotlin.get()}, kotlinx-coroutines ${it.kotlinxCoroutines.get()}, " +
      "kotlinx-serialization ${it.kotlinxSerializationJSON.get()}"
  }
  doLast {
    runPzUntilInit(gameDir, "game")

    val log = console.readText()
    checkPlatformLog(log, console, "smokeTest")
    val widenerPrefix = "${logPrefix}access widener: "
    val widener = log.lines().lastOrNull { widenerPrefix in it }?.substringAfter(widenerPrefix)
    if (widener?.startsWith("ok") != true) {
      throw GradleException("$console: the access widener is not in effect: ${widener ?: "no access widener line"}")
    }
    // GameWindow.init loads client Lua before ZombieBuddy exits
    val noticePrefix = "[$zabricMod] restart notice: "
    val notice = log.lines().lastOrNull { noticePrefix in it }?.substringAfter(noticePrefix)
    if (notice != "not needed") {
      throw GradleException("$console: restart notice is '${notice ?: "missing"}', not 'not needed'")
    }
    // in this order: ZombieBuddy runs the preload mods' Main first, Zabric's first, so Kotlin is checked before
    // All-in-One's Kotlin Main runs
    val ordered = listOf(
      "[$zabricMod] access widener: ok (",
      "[ZombieBuddyKotlin] $kotlinLibVersion loaded",
      "[ZombieBuddyKotlin] ready: $kotlinVersions",
      "[Dont39AllInOne] $version loaded",
    )
    val disabled = mutableListOf<String>()
    val generated = glue.flatMap { (name, dir) ->
      val lines = dir.listFiles { file -> file.extension == "txt" }.orEmpty().flatMap { it.readLines() }
      if (lines.isEmpty()) return@flatMap emptyList()
      val statusPrefix = "$logPrefix$name: "
      val status = log.lines().lastOrNull { statusPrefix in it }?.substringAfter(statusPrefix)
      logger.lifecycle("$name: ${status ?: "no status line"}")
      // idle is fine (a target mod is not enabled)
      if (status?.startsWith("disabled") == true) disabled += "$statusPrefix$status"
      listOf(statusPrefix) + lines.mapNotNull { line ->
        val value = line.substringAfter(' ')
        when (line.substringBefore(' ')) {
          "patch" -> "Found patch class: $value"
          "lua" -> "Exposing global functions from class: $value"
          // ZombieBuddy applies patches whether or not the feature runs; only a running one shows they are needed
          "target" -> if (status?.startsWith("ready") == true || status?.startsWith("active") == true) {
            "Transformed: $value"
          } else {
            null
          }
          else -> null
        }
      }
    }
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
