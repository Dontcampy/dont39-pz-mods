package buildsrc.convention.widening

import org.gradle.api.file.RegularFileProperty
import javax.inject.Inject

/** A module's `.classtweaker` list (`pzWidening`, set by pz-widening). */
abstract class PzWidening @Inject constructor(
  /** Whether the module has a list. */
  val listed: Boolean,
) {
  /** The list plus the enclosing classes it makes public, as the game-start widening applies it; set when [listed]. */
  abstract val expanded: RegularFileProperty
}
