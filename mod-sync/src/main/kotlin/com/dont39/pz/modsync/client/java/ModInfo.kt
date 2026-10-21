package com.dont39.pz.modsync.client.java

import com.pzlauncher.zabric.entrypoint.Lifecycle
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.jar.JarFile

/**
 * A mod whose jar ZombieBuddy loads on a client: [folder] the mod folder, [id] its mod id (null when its `mod.info` has
 * none), [jar] the jar `javaJarFile` names, [pkg] its `javaPkgName`. [preload]: ZombieBuddy or Zabric loads it at game
 * start, before any folder override applies.
 */
internal data class JavaMod(
  val folder: Path,
  val id: String?,
  val name: String,
  val jar: Path,
  val pkg: String,
  val preload: Boolean,
)

/**
 * Reads the Java mods of a folder of mod folders by ZombieBuddy's rules (`JavaModInfo`, `Loader.loadMods`): the version
 * folder's `mod.info` and jar, else `common`'s, else `common`'s `mod.info` with the jar in the version folder; a jar
 * under `media/java/server/` is not loaded on a client, nor one that is missing. ZombieBuddy has no public parser.
 */
internal object ModInfo {
  private const val FILE = "mod.info"
  private const val COMMON = "common"
  private const val SERVER_ONLY = "media/java/server/"
  private const val ZB_PRELOAD = "ZB-Preload"

  private class Values(val lines: List<String>) {
    val jar = first("javajarfile=") { it.endsWith(".jar") }
    val pkg = first("javapkgname=") { true }
    val name = first("name=") { true }
    val preload = lines.lastOrNull { key(it, "javapreload=") }?.let { value(it).equals("true", ignoreCase = true) }
      ?: false

    // the game's own reading: the last id= line wins
    val id = lines.map { it.trim() }.lastOrNull { it.startsWith("id=") }?.removePrefix("id=")?.trim()
      ?.takeIf { it.isNotEmpty() }

    private fun first(key: String, valid: (String) -> Boolean): String? =
      lines.filter { key(it, key) }.map(::value).firstOrNull { it.isNotEmpty() && valid(it) }

    private fun key(line: String, key: String) = '=' in line && line.lowercase(Locale.ROOT).startsWith(key)

    private fun value(line: String) = line.split("=", limit = 2)[1].trim()
  }

  /** [versionFolder]: the game's version folder name for a mod folder (`ZomboidFileSystem.getModVersionDirName`). */
  fun javaMods(modsFolder: Path, versionFolder: (Path) -> String): List<JavaMod> {
    if (!Files.isDirectory(modsFolder)) {
      return emptyList()
    }
    val folders = Files.list(modsFolder).use { children -> children.filter { Files.isDirectory(it) }.sorted().toList() }
    return folders.mapNotNull { mod ->
      val version = mod.resolve(versionFolder(mod))
      val common = mod.resolve(COMMON)
      parse(mod, version, version) ?: parse(mod, common, common)
        ?: if (Files.isDirectory(version)) parse(mod, common, version) else null
    }
  }

  private fun parse(mod: Path, infDir: Path, jarDir: Path): JavaMod? {
    val inf = infDir.resolve(FILE)
    if (!Files.isDirectory(infDir) || !Files.isRegularFile(inf)) {
      return null
    }
    // UTF-8 as ZombieBuddy reads it, so a name shows as in the game; a malformed byte does not fail the read
    val values = Values(String(Files.readAllBytes(inf), Charsets.UTF_8).lines())
    val jarName = values.jar ?: return null
    val pkg = values.pkg ?: return null
    if (SERVER_ONLY in jarName) {
      return null
    }
    val jar = jarDir.resolve(jarName)
    if (!Files.isRegularFile(jar)) {
      return null
    }
    return JavaMod(
      mod,
      values.id,
      values.name ?: values.id ?: mod.fileName.toString(),
      jar,
      pkg,
      values.preload || preloadManifest(jar),
    )
  }

  private fun preloadManifest(jar: Path): Boolean {
    val attributes = try {
      JarFile(jar.toFile()).use { it.manifest?.mainAttributes }
    } catch (e: IOException) {
      throw IOException("cannot read the manifest of $jar: ${e.message}", e)
    } ?: return false
    return attributes.getValue(ZB_PRELOAD).equals("true", ignoreCase = true) ||
      attributes.getValue(Lifecycle.PRE_LAUNCH) != null
  }
}
