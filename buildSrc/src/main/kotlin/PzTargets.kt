package buildsrc.convention

import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import java.io.File
import javax.inject.Inject

/**
 * The other mods a feature compiles against (`pzTargets { workshopMod(...) }` in its build script), from the local
 * Steam Workshop downloads. Compile-only: never shipped.
 */
abstract class PzTargets @Inject constructor(private val workshop: File) {
  abstract val jars: ConfigurableFileCollection

  /** [mod] is the folder under the Workshop item's `mods/`. */
  fun workshopMod(id: Long, mod: String) {
    val dir = workshop.resolve("$id/mods/$mod/42/media/java")
    val found = dir.walkTopDown().filter { it.isFile && it.extension == "jar" }.toList()
    if (found.isEmpty()) {
      throw GradleException(
        "No jar of target mod $mod (Workshop item $id) under $dir: subscribe to it in Steam and let it download.",
      )
    }
    jars.from(found)
  }
}
