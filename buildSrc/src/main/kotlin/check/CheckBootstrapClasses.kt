package buildsrc.convention.check

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/** Fails if one of [classes] references a `kotlin/` type: they run before ZabricKotlin has installed Kotlin. */
@DisableCachingByDefault(because = "a check without outputs")
abstract class CheckBootstrapClasses : DefaultTask() {
  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val classes: ConfigurableFileCollection

  @TaskAction
  fun check() {
    val offenders = classes.asFileTree.matching { include("**/*.class") }.files.mapNotNull { file ->
      classRefs(file.readBytes(), "kotlin/").takeIf { it.isNotEmpty() }?.let { "${file.name}: ${it.joinToString()}" }
    }
    if (offenders.isNotEmpty()) {
      throw GradleException("Java bootstrap classes reference Kotlin types:\n" + offenders.joinToString("\n"))
    }
  }
}
