package buildsrc.convention

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider

/** Steam app id of Project Zomboid, the folder of its Workshop downloads. */
private const val PZ_APP_ID = 108600

const val PZ_GAME_JAR = "projectzomboid.jar"
const val ZOMBIEBUDDY_JAR = "ZombieBuddy.jar"

/** The game install (`pzDir`). */
fun Project.pzDir(): Provider<Directory> = machineDir("pzDir", "the Project Zomboid install")

/** The dedicated server install (`pzServerDir`). */
fun Project.pzServerDir(): Provider<Directory> = machineDir("pzServerDir", "the Project Zomboid Dedicated Server")

/** The game's user folder (`pzUserDir`, by default ~/Zomboid): logs and staged Workshop items. */
fun Project.pzUserDir(): Provider<Directory> = layout.projectDirectory.dir(
  providers.gradleProperty("pzUserDir").orElse(providers.systemProperty("user.home").map { "$it/Zomboid" }),
)

/** Steam's Workshop downloads of the game, in the Steam library that holds the install. */
fun Project.pzWorkshopContent(): Provider<Directory> = pzDir().map { it.dir("../../workshop/content/$PZ_APP_ID") }

// Lazy, so a project configures without the property; whatever reads it fails with the hint.
private fun Project.machineDir(name: String, what: String): Provider<Directory> {
  val hint = "$path: $name is not set; put the path of $what in ~/.gradle/gradle.properties (README.md)"
  // no throwing orElse: the configuration cache evaluates it even when the property is set
  val value = providers.gradleProperty(name).orElse("").map { it.ifBlank { throw GradleException(hint) } }
  return layout.projectDirectory.dir(value)
}
