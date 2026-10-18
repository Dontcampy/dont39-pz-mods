package buildsrc.convention

import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import javax.inject.Inject

/**
 * The other mods a feature compiles against (`pzTargets { workshopMod(...) }` in its build script), from the local
 * Steam Workshop downloads ([workshop]). Compile-only: never shipped.
 */
abstract class PzTargets @Inject constructor(private val workshop: Provider<Directory>) {
  abstract val jars: ConfigurableFileCollection

  /** [mod] is the folder under the Workshop item's `mods/`. */
  fun workshopMod(id: Long, mod: String) {
    jars.from(
      workshop.map {
        val dir = it.dir("$id/mods/$mod/42/media/java").asFile
        val found = dir.walkTopDown().filter { file -> file.isFile && file.extension == "jar" }.toList()
        if (found.isEmpty()) {
          throw GradleException(
            "No jar of target mod $mod (Workshop item $id) under $dir: subscribe to it in Steam and let it download.",
          )
        }
        found
      },
    )
  }
}
