package com.dont39.pz.modsync.client.java

import com.pzlauncher.zabric.mods.JavaMods
import com.pzlauncher.zabric.mods.LaunchOverride
import com.pzlauncher.zabric.relaunch.Relaunch
import java.nio.file.Path
import zombie.ZomboidFileSystem
import zombie.modding.ActiveMods
import zombie.network.GameClient

/**
 * A mod whose jar the loader loads on a client, as [JavaMods.Mod] reads it: [id] its mod id (null when its `mod.info`
 * has none), [jar] the jar `javaJarFile` names, [pkg] its `javaPkgName`. [preload]: it loads at game start, before any
 * folder override applies. A value of its own so that tests can make one.
 */
internal data class JavaMod(val id: String?, val name: String, val jar: Path, val pkg: String, val preload: Boolean)

/**
 * What a join asks of this launch about Java mods: Zabric's one-launch override, the jars that run, the game's mod
 * list, and the restart. On the game thread, except [current], [javaMods] and [servingJar], which a join's worker
 * calls. Item ids are Workshop ids in decimal, folders `mods` folders.
 */
internal interface Launch {
  /** The items this launch takes from other folders. */
  fun current(): Map<String, Path>

  /** The request the next launch will apply. */
  fun pending(): Map<String, Path>

  /** Replaces the request for the next launch. */
  fun request(modsByItem: Map<String, Path>)

  /** The Java mods in [modsFolder], none when it does not exist; throws naming a file that cannot be read. */
  fun javaMods(modsFolder: Path): List<JavaMod>

  /** The real path of the jar the game runs [pkg]'s classes from, or null when no loaded jar has them. */
  fun servingJar(pkg: String): Path?

  /**
   * The main menu's mod list, as the loader got it for the startup pass and every later pass of the main menu: mods
   * only another mod's `require=` pulls in are not in it, and the loader never looks at those.
   */
  fun mainMenuMods(): Set<String>

  /** Why the game cannot restart itself here, or null when it can. */
  fun relaunchUnavailable(): String?

  /** This launch's arguments after the program, as [relaunch] counts them. */
  fun arguments(): List<String>

  /**
   * Quits the game to start it again as this launch started, without the arguments at [omitted]. Returns once the
   * game quits; throws with the reason, and the game runs on, when it cannot.
   */
  fun relaunch(omitted: Set<Int>)
}

/** [Launch] in game, through Zabric's public API. */
internal object GameLaunch : Launch {
  override fun current(): Map<String, Path> = LaunchOverride.current()

  override fun pending(): Map<String, Path> = LaunchOverride.pending()

  override fun request(modsByItem: Map<String, Path>) = LaunchOverride.request(modsByItem)

  // getModVersionDirName reads only the folder and static final versions, so a worker may call it
  override fun javaMods(modsFolder: Path): List<JavaMod> =
    JavaMods.inFolder(modsFolder) { ZomboidFileSystem.instance.getModVersionDirName(it) }
      .map { JavaMod(it.id(), it.name(), it.jar(), it.pkg(), it.preload()) }

  override fun servingJar(pkg: String): Path? = JavaMods.servingJar(pkg)?.path()

  // loadMods("default") fills this list from default.txt and passes the same list to loadMods(List)
  override fun mainMenuMods(): Set<String> = ActiveMods.getById("default").mods.toSet()

  /** The server's mod list, which the join's pass gets, also without the mods `require=` pulls in. */
  fun serverMods(): Set<String> = GameClient.instance.serverMods.toSet()

  override fun relaunchUnavailable(): String? = Relaunch.unavailable()

  override fun arguments(): List<String> = Relaunch.arguments()

  override fun relaunch(omitted: Set<Int>) = Relaunch.restart(omitted)
}
