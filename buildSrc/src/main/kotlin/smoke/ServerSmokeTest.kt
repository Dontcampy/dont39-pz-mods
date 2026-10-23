package buildsrc.convention.smoke

import buildsrc.convention.mod.kotlinVersions
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.io.File
import java.util.UUID
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask

/**
 * Starts the dedicated server the way its ProjectZomboid64.json does, without Steam, with its own cache dir and a test
 * server that enables only ZombieBuddy, Zabric and ZabricKotlin, until ZombieBuddy's exit_after_game_init; then checks
 * the server's log. Three launches: with a test config that preloads only Zabric, as after ZabricKotlin's update to a
 * preload mod, which installs Kotlin from Main and registers ZabricKotlin; with that config again, which installs it at
 * game start; with the player's ZombieBuddy config, as players run it.
 */
@UntrackedTask(because = "launches the dedicated server")
abstract class ServerSmokeTest : DefaultTask() {
  @get:Internal
  abstract val serverDir: DirectoryProperty

  /** The test's own files: admin password, test ZombieBuddy config. */
  @get:Internal
  abstract val smokeDir: DirectoryProperty

  /** The server's -cachedir, with the staged mods under `mods/`. */
  @get:Internal
  abstract val cacheDir: DirectoryProperty

  @get:Input
  abstract val serverName: Property<String>

  /** ZabricKotlin's mod id. */
  @get:Input
  abstract val modId: Property<String>

  @get:Input
  abstract val version: Property<String>

  @get:Input
  abstract val zabricId: Property<String>

  @get:Input
  abstract val kotlinVersions: Property<String>

  init {
    group = "verification"
  }

  @TaskAction
  fun run() {
    val serverDir = serverDir.get().asFile
    val smokeDir = smokeDir.get().asFile
    val cache = cacheDir.get().asFile
    val server = serverName.get()
    val id = modId.get()
    val zabricMod = zabricId.get()
    val loaded = "[$id] ${version.get()} loaded"
    val ready = "[$id] ready: ${kotlinVersions.get()}"

    // the server asks for one on a new server name and would wait for console input
    val passwordFile = smokeDir.resolve("adminpassword.txt")
    if (!passwordFile.isFile) {
      passwordFile.writeText(UUID.randomUUID().toString())
    }
    // the server fills in defaults for every other key
    cache.resolve("Server").mkdirs()
    cache.resolve("Server/$server.ini").writeText(
      "Mods=${listOf("ZombieBuddy", zabricMod, id).joinToString(";")}\nWorkshopItems=\nMap=Muldraugh, KY\n" +
        "Public=false\nUPnP=false\n",
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

    val preLaunch = "[$zabricMod] preLaunch: $id"
    val registered = "[$zabricMod] $id registered as a ZombieBuddy preload mod"
    val restart = "[$zabricMod] restart the server once"
    fun expect(name: String, log: String, console: File, present: List<String>, absent: List<String>) {
      val where = "$console ($name)"
      requireLines(log, where, present)
      absent.filter { it in log }.takeIf { it.isNotEmpty() }?.let {
        throw GradleException("$where should not have:\n" + it.joinToString("\n") { line -> "  $line" })
      }
      requireOrder(log, where, listOf(loaded, ready))
    }

    val configDir = smokeDir.resolve("zombie_buddy")
    configDir.deleteRecursively()
    configDir.mkdirs()
    val config = configDir.resolve("config.json")
    fun preloadEntry(mod: String): Map<String, String> {
      val dir = cache.resolve("mods/$mod/42")
      val jar = dir.resolve("mod.info").readLines().first { it.startsWith("javaJarFile=") }.substringAfter('=').trim()
      return mapOf("infPath" to dir.resolve("mod.info").absolutePath, "jarPath" to dir.resolve(jar).absolutePath)
    }
    fun preloadIds(): List<String> =
      ((JsonSlurper().parse(config) as Map<*, *>)["preload_mods"] as Map<*, *>).keys.map { it.toString() }
    // as after this mod's update: Zabric preloaded, this mod not yet
    config.writeText(
      JsonOutput.prettyPrint(
        JsonOutput.toJson(
          mapOf(
            "trusted_authors" to emptyList<String>(),
            "preload_mods" to mapOf(zabricMod to preloadEntry(zabricMod)),
            "auto_fix_mod_order" to true,
            "fix_approval_dialog_cursor" to true,
          ),
        ),
      ),
    )

    val first = "not preloaded yet"
    launch(first, configDir).let { (log, console) ->
      checkPlatformLog(log, console, "serverSmokeTest", registrations = true)
      expect(
        first, log, console,
        present = listOf(loaded, "$ready; installed when the mod list loaded, not at game start", registered, restart),
        absent = listOf(preLaunch),
      )
      if (preloadIds() != listOf(zabricMod, id)) {
        throw GradleException("$config ($first): preload_mods should be [$zabricMod, $id]: ${preloadIds()}")
      }
    }

    val second = "preloaded"
    launch(second, configDir).let { (log, console) ->
      checkPlatformLog(log, console, "serverSmokeTest")
      expect(
        second, log, console,
        present = listOf(loaded, "$ready; installed at game start", preLaunch),
        absent = listOf(registered, restart),
      )
    }

    // last: on a fresh setup this server would register its own copy of the mod in the player's config
    val playerConfig = File(System.getProperty("user.home"), ".zombie_buddy/config.json")
    val playerIds = if (playerConfig.isFile) {
      ((JsonSlurper().parse(playerConfig) as Map<*, *>)["preload_mods"] as? Map<*, *>)?.keys ?: emptySet<Any>()
    } else {
      emptySet<Any>()
    }
    if (zabricMod !in playerIds || id !in playerIds) {
      throw GradleException(
        "$playerConfig has no preload entry for $zabricMod and $id, so the server would register its own copies " +
          "there; run :dont39-all-in-one:smokeTest (twice on a first registration), then serverSmokeTest again",
      )
    }
    val player = "player's config"
    launch(player, null).let { (log, console) ->
      checkPlatformLog(log, console, "serverSmokeTest")
      expect(
        player, log, console,
        present = listOf(loaded, "$ready; installed at game start", preLaunch),
        absent = listOf(registered, restart),
      )
    }
  }
}
