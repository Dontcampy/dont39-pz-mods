package com.dont39.pz.modsync

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest

/** Writes a Workshop item folder: [files] by path relative to the item folder. */
internal fun writeItem(folder: Path, files: Map<String, String>): Path {
  files.forEach { (path, text) ->
    val file = folder.resolve(path)
    Files.createDirectories(file.parent)
    Files.writeString(file, text)
  }
  return folder
}

internal val SERVER_FILES = mapOf(
  "mods/Alpha/common/.keep" to "",
  "mods/Alpha/42/mod.info" to "name=Alpha\nid=Alpha\n",
  "mods/Alpha/42/media/lua/client/Alpha.lua" to "print('alpha 2')\n",
  "mods/Alpha/42/media/lua/shared/Shared.lua" to "return {}\n",
  "mods/Alpha/42/media/java/alpha.jar" to "jar v1",
  // outside mods/: not offered
  "preview.png" to "png",
)

/** A jar with one class entry and [attributes] in its manifest; [entry] makes two jars differ. */
internal fun jar(vararg attributes: Pair<String, String>, entry: String = "com/example/alpha/A.class"): ByteArray {
  val manifest = Manifest().apply {
    mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
    attributes.forEach { (name, value) -> mainAttributes.putValue(name, value) }
  }
  val out = ByteArrayOutputStream()
  JarOutputStream(out, manifest).use { jar ->
    jar.putNextEntry(JarEntry(entry))
    jar.write(entry.toByteArray())
    jar.closeEntry()
  }
  return out.toByteArray()
}

internal fun writeBytes(file: Path, bytes: ByteArray): Path {
  Files.createDirectories(file.parent)
  Files.write(file, bytes)
  return file
}
