package buildsrc.convention

import buildsrc.convention.feature.FeatureInfo
import buildsrc.convention.feature.PzArtifact
import buildsrc.convention.feature.pzElements

// A feature the all-in-one mod bundles: a Lua-only one applies this alone, a Kotlin one through pz-feature. Adds the
// descriptor folder (FeatureInfo), which a bundled module that is no feature does not have.
plugins {
  id("buildsrc.convention.pz-bundled")
}

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
