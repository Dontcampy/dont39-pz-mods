package buildsrc.convention.mod

import org.gradle.api.provider.Property
import javax.inject.Inject

/** A shipped mod's identity from its `mod/42/mod.info` (`pzMod`, set by pz-mod). */
abstract class PzMod @Inject constructor(val modId: String, val javaPkgName: String) {
  /** The jar's folder under `42/`, as mod.info's javaJarFile names it; `media/java` by default. */
  abstract val jarFolder: Property<String>
}
