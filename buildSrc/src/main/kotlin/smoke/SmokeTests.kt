package buildsrc.convention.smoke

import buildsrc.convention.feature.PzArtifact
import buildsrc.convention.feature.pzIncoming
import buildsrc.convention.mod.PzMod
import buildsrc.convention.mod.kotlinVersions
import buildsrc.convention.mod.modInfoOf
import buildsrc.convention.mod.pzDir
import buildsrc.convention.mod.pzUserDir
import org.gradle.api.Project
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.project
import org.gradle.kotlin.dsl.register

/**
 * Registers `smokeTest` for this shipped mod (pz-mod), with Zabric deployed too and, for a Kotlin mod ([kotlin]),
 * ZabricKotlin, whose version the log must show. [configure] sets what is specific to the mod.
 */
fun Project.registerSmokeTest(
  kotlin: Boolean = false,
  configure: GameSmokeTest.() -> Unit = {},
): TaskProvider<GameSmokeTest> {
  val modId = extensions.getByType<PzMod>().modId
  val zabricId = modInfoOf("zabric").id
  val kotlinLib = if (kotlin) {
    val scope = configurations.dependencyScope("kotlinLib")
    dependencies.add(scope.name, dependencies.project(":zb-kt"))
    pzIncoming("kotlinLibFolder", scope, PzArtifact.MOD_FOLDER) to kotlinVersions()
  } else {
    null
  }
  return tasks.register<GameSmokeTest>("smokeTest") {
    description = "Launches Project Zomboid until ZombieBuddy has loaded $modId, then checks the log."
    dependsOn(tasks.named("deployWorkshop"), ":zabric:deployWorkshop")
    gameDir.set(pzDir())
    console.set(pzUserDir().map { it.file("console.txt") })
    this.modId.set(modId)
    // read when the task is realized: a convention plugin registers it before the module script sets the version
    this.version.set(project.version.toString())
    this.zabricId.set(zabricId)
    kotlinLib?.let { (folder, versions) ->
      dependsOn(":zb-kt:deployWorkshop")
      kotlinMod.from(folder)
      kotlinVersions.set(versions)
    }
    configure()
  }
}
