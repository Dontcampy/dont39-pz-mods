import buildsrc.convention.MergeClassTweakers
import buildsrc.convention.PzGlue
import buildsrc.convention.WidenJars
import buildsrc.convention.checkPlatformLog
import buildsrc.convention.runPzUntilInit

plugins {
  id("buildsrc.convention.pz-kotlin")
  id("buildsrc.convention.pz-mod")
}

version = "0.6.0"

val modId = extra["modId"] as String
@Suppress("UNCHECKED_CAST")
val pzUserDir = extra["pzUserDir"] as Provider<String>
val features = listOf(":mod-options-sidebar", ":ime-keyboard-fix")
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
  compileOnly(project(zabric))
}

val javaPkgName = file("mod/42/mod.info").readLines().firstOrNull { it.startsWith("javaPkgName=") }
  ?.substringAfter('=')?.trim() ?: throw GradleException("mod/42/mod.info has no javaPkgName= line")
features.forEach {
  project(it).extensions.findByType<PzGlue>()?.let { glue ->
    glue.gluePackage = javaPkgName
    glue.luaPrefix = "${modId}_"
    glue.modId = modId
  }
}

// each feature's generated listings, by feature module name: Zabric's hook listings (one file per hook class) and the
// Lua bridge's
val hookDirs = features.associate {
  val resources = project(it).the<SourceSetContainer>()["main"].output.resourcesDir!!
  project(it).name to resources.resolve("META-INF/zabric/hooks")
}
val luaDirs = features.associate {
  val resources = project(it).the<SourceSetContainer>()["main"].output.resourcesDir!!
  project(it).name to resources.resolve("META-INF/zbkt/glue")
}

val mergeClassTweakers = tasks.register<MergeClassTweakers>("mergeClassTweakers") {
  lists.from(features.flatMap { project(it).tasks.withType<WidenJars>().map { task -> task.expanded } })
  merged = layout.buildDirectory.file("classtweaker/$modId.classtweaker")
}

tasks.jar {
  manifest { attributes("Zabric-Main" to "$javaPkgName.AllInOneMain") }
  features.forEach { from(project(it).the<SourceSetContainer>()["main"].output) }
  // build data only (the features' source .classtweaker lists)
  exclude("META-INF/dont39/**")
  // where Zabric reads it at game start
  from(mergeClassTweakers) { into("META-INF/zabric") }
  duplicatesStrategy = DuplicatesStrategy.FAIL
}

val checkGlue = tasks.register("checkGlue") {
  group = "verification"
  description = "Checks that the features' hook listings name this mod and package, and that no two features " +
    "generate the same global Lua function."
  dependsOn(features.map { "$it:processResources" })
  val hooks = hookDirs
  val lua = luaDirs
  val mod = modId
  val pkg = javaPkgName
  doLast {
    fun lines(dir: File) =
      dir.listFiles { file -> file.extension == "txt" }.orEmpty().sorted().flatMap { it.readLines() }

    // Zabric opens hooks by the listing's mod id; ZombieBuddy finds patch classes only in exactly javaPkgName
    val wrong = hooks.flatMap { (feature, dir) ->
      val lines = lines(dir)
      lines.filter { it.startsWith("mod ") && it != "mod $mod" }.map { "$feature: '$it', not $mod" } +
        lines.filter { it.startsWith("patch ") && it.substringAfter(' ').substringBeforeLast('.') != pkg }
          .map { "$feature: '$it', not in $pkg" }
    }
    if (wrong.isNotEmpty()) {
      throw GradleException("Hook listings that do not match this mod:\n" + wrong.joinToString("\n") { "  $it" })
    }
    // Lua globals are shared, so names must differ across features too
    val owners = lua.flatMap { (feature, dir) ->
      lines(dir).filter { it.startsWith("luaGlobal ") }.map { it.substringAfter(' ') to feature }
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
  val hooks = hookDirs
  val lua = luaDirs
  val logPrefix = "[$modId] "
  val zabricMod = zabricId
  val hooksReady = "[$zabricId] $modId: hooks ready ("
  val mainLine = "[$zabricId] main: $modId"
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
    // in this order: ZombieBuddyKotlin's Main logs Kotlin's state before ZombieBuddy reaches the mods that require it,
    // and the main entrypoints run after the pass
    val ordered = listOf(
      "[$zabricMod] access widener: ok (",
      "[ZombieBuddyKotlin] $kotlinLibVersion loaded",
      "[ZombieBuddyKotlin] ready: $kotlinVersions",
      mainLine,
      "[Dont39AllInOne] $version loaded",
    )
    fun lines(dir: File) =
      dir.listFiles { file -> file.extension == "txt" }.orEmpty().sorted().flatMap { it.readLines() }

    val disabled = mutableListOf<String>()
    val generated = hooks.keys.flatMap { name ->
      val lines = lines(hooks.getValue(name)) + lines(lua.getValue(name))
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
          "hook" -> if (status?.startsWith("ready") == true || status?.startsWith("active") == true) {
            "Transformed: ${value.substringBefore(' ')}"
          } else {
            null
          }
          else -> null
        }
      }
    }
    val luaOnly = listOf("${logPrefix}mod-options-sidebar: ready")
    val ready = listOf(hooksReady).filter { hooks.values.any { dir -> lines(dir).isNotEmpty() } }
    val missing = (ordered + generated + luaOnly + ready).distinct().filterNot { it in log }
    if (missing.isNotEmpty()) {
      throw GradleException("$console is missing:\n" + missing.joinToString("\n") { "  $it" })
    }
    // any mod's, not only this one's: their hooks run inside the same game methods
    val stopped = log.lines().filter { "[$zabricMod] " in it && ": hooks stopped" in it }
    if (stopped.isNotEmpty()) {
      throw GradleException("$console has hooks Zabric stopped:\n" + stopped.joinToString("\n") { "  $it" })
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
