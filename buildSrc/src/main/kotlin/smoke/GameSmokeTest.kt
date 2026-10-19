package buildsrc.convention.smoke

import buildsrc.convention.feature.FeatureInfo
import buildsrc.convention.mod.kotlinVersions
import buildsrc.convention.mod.modInfoValue
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask

/**
 * Starts the game the way ProjectZomboid64.exe would, with ZombieBuddy's exit_after_game_init, then checks the console
 * log for the platform mods and every bundled feature. Needs Steam running; loads whichever mods are enabled in the
 * game's mod list.
 */
@UntrackedTask(because = "launches the game")
abstract class GameSmokeTest : DefaultTask() {
  @get:Internal
  abstract val gameDir: DirectoryProperty

  @get:Internal
  abstract val console: RegularFileProperty

  @get:Input
  abstract val modId: Property<String>

  @get:Input
  abstract val version: Property<String>

  @get:Input
  abstract val zabricId: Property<String>

  /** ZabricKotlin's assembled mod folder: its mod.info has the version the log must show. */
  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val kotlinMod: ConfigurableFileCollection

  @get:Input
  abstract val kotlinVersions: Property<String>

  /** The bundled features' descriptor folders. */
  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val featureFolders: ConfigurableFileCollection

  /** Lua-only features with a target mod: they log a status, and idle passes. The others must log ready. */
  @get:Input
  abstract val luaTargeted: ListProperty<String>

  init {
    group = "verification"
  }

  @TaskAction
  fun run() {
    val modId = modId.get()
    val zabric = zabricId.get()
    val kotlinModInfo = kotlinMod.singleFile.resolve("42/mod.info")
    val kotlinVersion = modInfoValue(kotlinModInfo.readLines(), "modversion", kotlinModInfo)
    val features = featureFolders.files.map(FeatureInfo::read)
    val targeted = luaTargeted.get()
    val luaOnly = features.filter { it.facade == null }.map { it.name }
    (targeted - luaOnly.toSet()).takeIf { it.isNotEmpty() }?.let {
      throw GradleException("luaTargeted names $it, which are not Lua-only features ($luaOnly)")
    }
    val console = console.get().asFile

    runPzUntilInit(gameDir.get().asFile, "game")

    val log = console.readText()
    checkPlatformLog(log, console, "smokeTest")
    val logPrefix = "[$modId] "
    val widenerPrefix = "${logPrefix}access widener: "
    val widener = log.lines().lastOrNull { widenerPrefix in it }?.substringAfter(widenerPrefix)
    if (widener?.startsWith("ok") != true) {
      throw GradleException("$console: the access widener is not in effect: ${widener ?: "no access widener line"}")
    }
    // GameWindow.init loads client Lua before ZombieBuddy exits
    val noticePrefix = "[$zabric] restart notice: "
    val notice = log.lines().lastOrNull { noticePrefix in it }?.substringAfter(noticePrefix)
    if (notice != "not needed") {
      throw GradleException("$console: restart notice is '${notice ?: "missing"}', not 'not needed'")
    }
    // in this order: ZabricKotlin's Main logs Kotlin's state before ZombieBuddy reaches the mods that require it,
    // and the main entrypoints run after the pass
    val ordered = listOf(
      "[$zabric] access widener: ok (",
      "[ZabricKotlin] $kotlinVersion loaded",
      "[ZabricKotlin] ready: ${kotlinVersions.get()}",
      "[$zabric] main: $modId",
      "$logPrefix${version.get()} loaded",
    )

    val disabled = mutableListOf<String>()
    fun status(name: String): String? {
      val statusPrefix = "$logPrefix$name: "
      val status = log.lines().lastOrNull { statusPrefix in it }?.substringAfter(statusPrefix)
      logger.lifecycle("$name: ${status ?: "no status line"}")
      // idle is fine (a target mod is not enabled)
      if (status?.startsWith("disabled") == true) disabled += "$statusPrefix$status"
      return status
    }
    val generated = features.flatMap { feature ->
      val lines = feature.hookLines + feature.luaLines
      if (lines.isEmpty()) return@flatMap emptyList()
      val status = status(feature.name)
      listOf("$logPrefix${feature.name}: ") + lines.mapNotNull { line ->
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
    val luaReady = (luaOnly - targeted.toSet()).map { "$logPrefix$it: ready" }
    val luaStatus = targeted.map { name ->
      status(name)
      "$logPrefix$name: "
    }
    val hooksReady = listOf("[$zabric] $modId: hooks ready (").filter { features.any { it.hookLines.isNotEmpty() } }
    requireLines(log, "$console", (ordered + generated + luaReady + luaStatus + hooksReady).distinct())
    // any mod's, not only this one's: their hooks run inside the same game methods
    val stopped = log.lines().filter { "[$zabric] " in it && ": hooks stopped" in it }
    if (stopped.isNotEmpty()) {
      throw GradleException("$console has hooks Zabric stopped:\n" + stopped.joinToString("\n") { "  $it" })
    }
    if (disabled.isNotEmpty()) {
      throw GradleException(
        "$console has features that disabled themselves:\n" + disabled.joinToString("\n") { "  $it" },
      )
    }
    requireOrder(log, "$console", ordered)
  }
}
