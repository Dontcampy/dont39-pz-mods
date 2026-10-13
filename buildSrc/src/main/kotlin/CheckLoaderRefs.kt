package buildsrc.convention

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/** The loader's packages, in internal form. */
private const val LOADER = "me/zed_0xff/"

/**
 * Fails if one of [classes] references a type of the loader (ZombieBuddy). Only Zabric's loader package and the
 * generated glue may, so that switching loaders stays in those two places; they are left out with [excludes] (Ant
 * patterns relative to the class folders).
 */
@DisableCachingByDefault(because = "a check without outputs")
abstract class CheckLoaderRefs : DefaultTask() {
  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val classes: ConfigurableFileCollection

  @get:Input
  abstract val excludes: SetProperty<String>

  init {
    excludes.convention(emptySet())
  }

  @TaskAction
  fun check() {
    val excluded = excludes.get()
    val offenders = mutableListOf<String>()
    classes.asFileTree.matching {
      include("**/*.class")
      exclude(excluded)
    }.visit {
      if (!isDirectory) {
        val refs = classRefs(file.readBytes(), LOADER)
        if (refs.isNotEmpty()) offenders += "${relativePath.pathString}: ${refs.joinToString()}"
      }
    }
    if (offenders.isNotEmpty()) {
      throw GradleException(
        "Classes outside Zabric's loader package and the generated glue reference the loader ($LOADER):\n" +
          offenders.joinToString("\n"),
      )
    }
  }
}
