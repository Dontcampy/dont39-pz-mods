package com.dont39.pz.modsync.protocol

import java.nio.file.Path

/**
 * Paths in the manifest come from the server, so only the plain form is accepted (never rewritten): relative, `/`
 * separators, under `mods/`, and nothing a Windows client would read differently than the Linux server wrote it.
 */
internal object SafePath {
  const val MODS = "mods"

  private val DRIVE = Regex("^[A-Za-z]:")
  private val DEVICE = Regex("(?i)(con|prn|aux|nul|com[0-9]|lpt[0-9])(\\..*)?")
  private const val FORBIDDEN = "\\:*?\"<>|"

  /** [path] when the manifest may list it; else throws [SyncException] naming why not. */
  fun check(path: String): String {
    val problem = problem(path) ?: return path
    throw SyncException("the server's mod list has the path \"$path\", which $problem")
  }

  /** Where [path] lies under [root]; throws [SyncException] when it is not a manifest path or would leave [root]. */
  fun resolve(root: Path, path: String): Path {
    check(path)
    val base = root.toAbsolutePath().normalize()
    val resolved = base.resolve(path).normalize()
    if (resolved == base || !resolved.startsWith(base)) {
      throw SyncException("the server's mod list has the path \"$path\", which leaves its folder")
    }
    return resolved
  }

  private fun problem(path: String): String? {
    if (path.isEmpty()) {
      return "is empty"
    }
    if (path.startsWith("/") || DRIVE.containsMatchIn(path)) {
      return "is absolute"
    }
    path.firstOrNull { it < ' ' || it == '\u007f' || it in FORBIDDEN }?.let { return "contains ${describe(it)}" }
    val segments = path.split('/')
    if (segments.size < 2 || segments[0] != MODS) {
      return "is not under $MODS/"
    }
    segments.forEach { s ->
      when {
        s.isEmpty() -> return "has an empty part"
        s == "." || s == ".." -> return "has a \"$s\" part"
        s.endsWith('.') || s.endsWith(' ') -> return "has a part ending in a dot or space, which Windows drops"
        DEVICE.matches(s) -> return "has the Windows device name \"$s\""
      }
    }
    return null
  }

  private fun describe(c: Char): String = when {
    c == '\\' -> "a backslash"
    c < ' ' || c == '\u007f' -> "the control character U+%04X".format(c.code)
    else -> "the character '$c'"
  }
}
