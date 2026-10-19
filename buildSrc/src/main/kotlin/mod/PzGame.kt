package buildsrc.convention.mod

import org.gradle.api.file.RegularFileProperty

/** The game a module compiles against (`pzGame`, set by pz-java). */
interface PzGame {
  /** projectzomboid.jar; pz-feature swaps in a widened copy. */
  val gameJar: RegularFileProperty
}
