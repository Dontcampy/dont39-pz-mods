package buildsrc.convention

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.file.RegularFile
import java.io.File

/** What the build reads of a mod's `mod/42/mod.info`. */
class ModInfo(val id: String, val javaPkgName: String)

/** Reads [file] at configuration time, as an input of the configuration cache. */
fun Project.readModInfo(file: RegularFile): ModInfo {
  val text = providers.fileContents(file).asText.orNull ?: throw GradleException("${file.asFile} does not exist")
  val lines = text.lines()
  return ModInfo(modInfoValue(lines, "id", file.asFile), modInfoValue(lines, "javaPkgName", file.asFile))
}

/** The mod.info of the mod module in [dir] (relative to the root), read as a file rather than from its project. */
fun Project.modInfoOf(dir: String): ModInfo = readModInfo(layout.settingsDirectory.dir(dir).file("mod/42/mod.info"))

/** The value of [key] in the mod.info [lines] read from [file]. */
fun modInfoValue(lines: List<String>, key: String, file: File): String =
  lines.firstOrNull { it.startsWith("$key=") }?.substringAfter('=')?.trim()
    ?: throw GradleException("$file has no $key= line")
