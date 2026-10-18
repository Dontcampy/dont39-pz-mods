package buildsrc.convention

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/**
 * Fails unless the bundled features' hook listings name this mod ([modId]) and package ([javaPkgName]), or if two
 * features generate the same global Lua function.
 */
@DisableCachingByDefault(because = "a check without outputs")
abstract class CheckGlue : DefaultTask() {
  /** The features' descriptor folders. */
  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val featureFolders: ConfigurableFileCollection

  @get:Input
  abstract val modId: Property<String>

  @get:Input
  abstract val javaPkgName: Property<String>

  init {
    group = "verification"
    description = "Checks that the features' hook listings name this mod and package, and that no two features " +
      "generate the same global Lua function."
  }

  @TaskAction
  fun check() {
    val all = featureFolders.files.map(FeatureInfo::read)
    val mod = modId.get()
    val pkg = javaPkgName.get()
    // Zabric opens hooks by the listing's mod id; ZombieBuddy finds patch classes only in exactly javaPkgName
    val wrong = all.flatMap { feature ->
      val lines = feature.hookLines
      lines.filter { it.startsWith("mod ") && it != "mod $mod" }.map { "${feature.name}: '$it', not $mod" } +
        lines.filter { it.startsWith("patch ") && it.substringAfter(' ').substringBeforeLast('.') != pkg }
          .map { "${feature.name}: '$it', not in $pkg" }
    }
    if (wrong.isNotEmpty()) {
      throw GradleException("Hook listings that do not match this mod:\n" + wrong.joinToString("\n") { "  $it" })
    }
    // Lua globals are shared, so names must differ across features too
    val owners = all.flatMap { feature ->
      feature.luaLines.filter { it.startsWith("luaGlobal ") }.map { it.substringAfter(' ') to feature.name }
    }.groupBy({ it.first }, { it.second })
    val clashes = owners.filterValues { it.size > 1 }
    if (clashes.isNotEmpty()) {
      throw GradleException(
        "Global Lua functions declared by more than one feature:\n" +
          clashes.entries.joinToString("\n") { (name, by) -> "  $name: ${by.joinToString()}" },
      )
    }
  }
}
