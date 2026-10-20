package buildsrc.convention

import buildsrc.convention.feature.PzArtifact
import buildsrc.convention.feature.pzElements

// A module the all-in-one mod bundles. Publishes its Lua and media files and its .classtweaker list (pz-feature adds
// it), so the mod reads none of this project's model.
plugins {
  id("buildsrc.convention.pz-jvm")
}

val modFiles = layout.buildDirectory.dir("pz/mod-files")
val stageModFiles = tasks.register<Sync>("stageModFiles") {
  description = "Stages ${project.name}'s Lua and media files as the bundling mod lays them out under 42/media."
  into(modFiles)
  from("src/main/lua") { into("lua") }
  // other media files, e.g. sandbox-options.txt
  from("src/main/media")
  duplicatesStrategy = DuplicatesStrategy.FAIL
}
pzElements("pzModFilesElements", PzArtifact.MOD_FILES).configure {
  outgoing.artifact(modFiles) { builtBy(stageModFiles) }
}

pzElements("pzClassTweakerElements", PzArtifact.CLASSTWEAKER)
