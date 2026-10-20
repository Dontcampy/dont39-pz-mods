package com.dont39.pz.modsync.client.download

import com.dont39.pz.modsync.protocol.ManifestFile
import com.dont39.pz.modsync.protocol.SyncException
import com.dont39.pz.modsync.protocol.sha256
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

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
    var count = 0L
    val hash = Files.newOutputStream(temp).use { out ->
      // before every read: here for the first, after each chunk for the next
      progress?.checkCancelled()
      sha256(input) { buffer, n ->
        count += n
        // a server that sends more than it listed would otherwise fill the disk
        if (count > file.size) {
          throw SyncException("${file.path} from $source is larger than the ${file.size} bytes the server's list gives")
        }
        out.write(buffer, 0, n)
        progress?.addBytes(n)
        progress?.checkCancelled()
      }
    }
    if (count != file.size) {
      throw SyncException("${file.path} from $source has $count of the ${file.size} bytes the server's list gives")
    }
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
