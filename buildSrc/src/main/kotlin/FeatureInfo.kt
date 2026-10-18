package buildsrc.convention

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.util.Properties

/**
 * A bundled feature as its descriptor folder ([PzArtifact.FEATURE]) describes it: the module [name], the [facade] whose
 * `init()` the mod calls (null for a Lua-only feature), and the hook and Lua listings its glue generator wrote.
 */
class FeatureInfo(val name: String, val facade: String?, val hookLines: List<String>, val luaLines: List<String>) {
  companion object {
    const val DESCRIPTOR = "feature.properties"
    const val NAME = "name"
    const val FACADE = "facade"
    const val HOOKS = "META-INF/zabric/hooks"
    const val LUA = "META-INF/zbkt/glue"

    fun read(dir: File): FeatureInfo {
      val file = dir.resolve(DESCRIPTOR)
      if (!file.isFile) throw GradleException("$dir has no $DESCRIPTOR")
      val properties = Properties().apply { file.reader().use { load(it) } }
      val name = properties.getProperty(NAME) ?: throw GradleException("$file has no $NAME")
      return FeatureInfo(name, properties.getProperty(FACADE), lines(dir.resolve(HOOKS)), lines(dir.resolve(LUA)))
    }

    private fun lines(dir: File) =
      dir.listFiles { file -> file.extension == "txt" }.orEmpty().sorted().flatMap { it.readLines() }
  }
}

/** Writes `initFeatures()`, which calls each feature facade's `init()` in [order]. */
@CacheableTask
abstract class GenerateFeatureInit : DefaultTask() {
  /** The features' descriptor folders. */
  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val featureFolders: ConfigurableFileCollection

  /** Every feature's module name, in init order. */
  @get:Input
  abstract val order: ListProperty<String>

  @get:Input
  abstract val packageName: Property<String>

  @get:OutputDirectory
  abstract val outputDir: DirectoryProperty

  @TaskAction
  fun generate() {
    val byName = featureFolders.files.map(FeatureInfo::read).associateBy { it.name }
    val names = order.get()
    if (byName.keys != names.toSet()) {
      throw GradleException("Feature descriptors ${byName.keys.sorted()} do not match the features list $names")
    }
    val pkg = packageName.get()
    val calls = names.mapNotNull { byName.getValue(it).facade }.joinToString("") { "  $it.init()\n" }
    val dir = outputDir.get().asFile
    dir.deleteRecursively()
    val file = dir.resolve(pkg.replace('.', '/')).resolve("GeneratedFeatures.kt")
    file.parentFile.mkdirs()
    file.writeText(
      "// Generated from the features list of $FEATURE_BUNDLE. Do not edit.\n" +
        "package $pkg\n\n" +
        "internal fun initFeatures() {\n$calls}\n",
    )
  }
}
