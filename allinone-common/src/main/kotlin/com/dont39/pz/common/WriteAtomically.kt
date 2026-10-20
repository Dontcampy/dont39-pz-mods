package com.dont39.pz.common

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/**
 * [write] fills the `<name>.tmp` sibling it is given, which then replaces [path]: a crash mid-write leaves the old
 * file.
 */
fun writeAtomically(path: Path, write: (Path) -> Unit) {
  Files.createDirectories(path.parent)
  val temp = path.resolveSibling("${path.fileName}.tmp")
  write(temp)
  Files.move(temp, path, REPLACE_EXISTING, ATOMIC_MOVE)
}
