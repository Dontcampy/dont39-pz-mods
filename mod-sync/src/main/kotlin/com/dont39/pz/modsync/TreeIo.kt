package com.dont39.pz.modsync

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.util.HexFormat

private const val BUFFER_SIZE = 64 * 1024

internal fun sha256(path: Path): String = Files.newInputStream(path).use { input ->
  val digest = MessageDigest.getInstance("SHA-256")
  val buffer = ByteArray(BUFFER_SIZE)
  while (true) {
    val n = input.read(buffer)
    if (n < 0) {
      break
    }
    digest.update(buffer, 0, n)
  }
  HexFormat.of().formatHex(digest.digest())
}

/** [file] relative to [root] with `/` separators, as the manifest writes paths. */
internal fun relativePath(root: Path, file: Path): String = root.relativize(file).joinToString("/")

internal fun regularFiles(folder: Path): List<Path> =
  Files.walk(folder).use { files -> files.filter { Files.isRegularFile(it) }.toList() }

internal fun deleteTree(path: Path) {
  if (!Files.exists(path, NOFOLLOW_LINKS)) {
    return
  }
  Files.walk(path).use { files -> files.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
}

/**
 * Streams [input] into [target] through a temporary file next to it, which becomes [target] only when its size and
 * SHA-256 are [file]'s: a cut or wrong copy never takes the target's place. [source] names where the bytes came from
 * in the reason.
 */
internal fun writeVerified(input: InputStream, target: Path, file: ManifestFile, source: String, progress: Progress?) {
  Files.createDirectories(target.parent)
  val temp = Files.createTempFile(target.parent, ".part-", ".tmp")
  try {
    val digest = MessageDigest.getInstance("SHA-256")
    var count = 0L
    Files.newOutputStream(temp).use { out ->
      val buffer = ByteArray(BUFFER_SIZE)
      while (true) {
        progress?.checkCancelled()
        val n = input.read(buffer)
        if (n < 0) {
          break
        }
        count += n
        // a server that sends more than it listed would otherwise fill the disk
        if (count > file.size) {
          throw SyncException("${file.path} from $source is larger than the ${file.size} bytes the server's list gives")
        }
        digest.update(buffer, 0, n)
        out.write(buffer, 0, n)
        progress?.addBytes(n)
      }
    }
    if (count != file.size) {
      throw SyncException("${file.path} from $source has $count of the ${file.size} bytes the server's list gives")
    }
    val hash = HexFormat.of().formatHex(digest.digest())
    if (hash != file.sha256) {
      throw SyncException(
        "${file.path} from $source has the SHA-256 $hash, not ${file.sha256} as the server's list gives",
      )
    }
    Files.move(temp, target, REPLACE_EXISTING, ATOMIC_MOVE)
  } finally {
    Files.deleteIfExists(temp)
  }
}
