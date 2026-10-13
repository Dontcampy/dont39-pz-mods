package com.dont39.pz.zbktutils

import me.zed_0xff.zombie_buddy.Patch
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

/** Where zb-kt-utils-processor lists a feature's generated glue; shipped in the mod jar. */
private const val GLUE_LISTING_DIR = "META-INF/zbkt/glue/"

/**
 * Throws naming a class or method that [feature]'s generated patches hook but that is not there; ZombieBuddy would leave
 * such a hook out without an error. The patch classes come from the glue listing in [host]'s own jar: all mod jars
 * share one class loader, so a resource lookup could find another mod's listing.
 */
internal fun checkHookTargets(feature: String, host: Class<*>) {
  val listing = GLUE_LISTING_DIR + feature.split('-').joinToString("") { it.replaceFirstChar(Char::uppercaseChar) } +
    ".txt"
  val patches = readListing(listing, host).filter { it.startsWith("patch ") }.map { it.substringAfter(' ') }
  for (name in patches) {
    val patch = try {
      Class.forName(name, false, host.classLoader)
    } catch (e: ClassNotFoundException) {
      throw IllegalStateException("$listing names $name, which is not in the jar", e)
    }
    val target = patch.getAnnotation(Patch::class.java) ?: error("$name, named in $listing, has no @Patch")
    val owner = try {
      Class.forName(target.className, false, host.classLoader)
    } catch (e: ClassNotFoundException) {
      throw IllegalStateException("class ${target.className}, which ${patch.name} hooks, is not there", e)
    }
    check(owner.declaredMethods.any { it.name == target.methodName }) {
      "${target.className} has no method ${target.methodName}, which ${patch.name} hooks"
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
