package com.dont39.pz.zbktutils

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

/** Where zb-kt-utils-processor lists a feature's generated glue; shipped in the mod jar. */
private const val GLUE_LISTING_DIR = "META-INF/zbkt/glue/"

/**
 * Throws naming a class or method that [feature]'s generated patches hook but that is not there; the loader would leave
 * such a hook out without an error. The hooks come from the glue listing in [host]'s own jar: all mod jars share one
 * class loader, so a resource lookup could find another mod's listing.
 */
internal fun checkHookTargets(feature: String, host: Class<*>) {
  val listing = GLUE_LISTING_DIR + feature.split('-').joinToString("") { it.replaceFirstChar(Char::uppercaseChar) } +
    ".txt"
  val lines = readListing(listing, host)
  lines.filter { it.startsWith("patch ") }.map { it.substringAfter(' ') }.forEach { name ->
    try {
      Class.forName(name, false, host.classLoader)
    } catch (e: ClassNotFoundException) {
      throw IllegalStateException("$listing names $name, which is not in the jar", e)
    }
  }
  lines.filter { it.startsWith("hook ") }.forEach { line ->
    val parts = line.split(' ')
    check(parts.size == 3) { "$listing has a malformed line: $line" }
    val (_, className, methodName) = parts
    val owner = try {
      Class.forName(className, false, host.classLoader)
    } catch (e: ClassNotFoundException) {
      throw IllegalStateException("class $className, which $feature hooks, is not there", e)
    }
    check(owner.declaredMethods.any { it.name == methodName }) {
      "$className has no method $methodName, which $feature hooks"
    }
  }
}

private fun readListing(listing: String, host: Class<*>): List<String> {
  val location = host.protectionDomain.codeSource?.location ?: error("${host.name} has no code source")
  val path = Path.of(location.toURI())
  if (Files.isDirectory(path)) {
    val file = path.resolve(listing)
    check(Files.isRegularFile(file)) { "$file is missing" }
    return Files.readAllLines(file)
  }
  return ZipFile(path.toFile()).use { zip ->
    val entry = zip.getEntry(listing) ?: error("$path has no $listing")
    zip.getInputStream(entry).bufferedReader().readLines()
  }
}
