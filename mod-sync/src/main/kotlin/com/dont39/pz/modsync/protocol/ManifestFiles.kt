package com.dont39.pz.modsync.protocol

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

/** Reads [input] to its end, handing each chunk to [chunk] as it is read; returns the SHA-256 of all of it. */
internal inline fun sha256(input: InputStream, chunk: (buffer: ByteArray, n: Int) -> Unit): String {
  val digest = MessageDigest.getInstance("SHA-256")
  val buffer = ByteArray(64 * 1024)
  while (true) {
    val n = input.read(buffer)
    if (n < 0) {
      break
    }
    chunk(buffer, n)
    digest.update(buffer, 0, n)
  }
  return HexFormat.of().formatHex(digest.digest())
}

/** The hash a [ManifestFile] gives for [path]. */
internal fun sha256(path: Path): String = Files.newInputStream(path).use { input -> sha256(input) { _, _ -> } }

/** [file] relative to [root] with `/` separators, as the manifest writes paths. */
internal fun relativePath(root: Path, file: Path): String = root.relativize(file).joinToString("/")

internal fun regularFiles(folder: Path): List<Path> =
  Files.walk(folder).use { files -> files.filter { Files.isRegularFile(it) }.toList() }
