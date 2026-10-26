package buildsrc.convention.check

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Fails unless [classes] contain [className], which the jar's manifest names under [attribute]: Zabric would only
 * report the missing class in game.
 */
@CacheableTask
abstract class CheckEntrypoint : DefaultTask() {
  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val classes: ConfigurableFileCollection

  /** The manifest attribute, e.g. `Zabric-Main`. */
  @get:Input
  abstract val attribute: Property<String>

  /** Binary name, e.g. `com.example.mod.ExampleMain`. */
  @get:Input
  abstract val className: Property<String>

  /** Records the class checked, so the check is up to date while its inputs are. */
  @get:OutputFile
  abstract val marker: RegularFileProperty

  init {
    group = "verification"
  }

  @TaskAction
  fun check() {
    val name = className.get()
    val file = name.replace('.', '/') + ".class"
    if (classes.files.none { it.resolve(file).isFile }) {
      throw GradleException(
        "The jar's manifest names ${attribute.get()}: $name, but no such class was compiled; the name follows the " +
          "module name, so rename the class or the module.",
      )
    }
    marker.get().asFile.writeText("$name\n")
  }
}
