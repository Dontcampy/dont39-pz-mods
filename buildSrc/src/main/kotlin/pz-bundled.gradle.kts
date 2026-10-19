package buildsrc.convention

import buildsrc.convention.feature.FeatureInfo
import buildsrc.convention.feature.PzArtifact
import buildsrc.convention.feature.pzElements

// A feature module the all-in-one mod bundles. Publishes its Lua and media files, its .classtweaker list (pz-feature
// adds it) and its descriptor folder (FeatureInfo), so the mod reads none of this project's model.
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

val descriptor = tasks.register<WriteProperties>("featureDescriptor") {
  description = "Writes ${project.name}'s feature descriptor for the bundling mod."
  destinationFile = layout.buildDirectory.file("pz/descriptor/${FeatureInfo.DESCRIPTOR}")
  property(FeatureInfo.NAME, project.name)
}
val featureFolder = layout.buildDirectory.dir("pz/feature")
val stageFeature = tasks.register<Sync>("stageFeatureInfo") {
  description = "Stages ${project.name}'s descriptor and generated listings for the bundling mod."
  into(featureFolder)
  from(descriptor)
}
pzElements("pzFeatureElements", PzArtifact.FEATURE).configure {
  outgoing.artifact(featureFolder) { builtBy(stageFeature) }
}
