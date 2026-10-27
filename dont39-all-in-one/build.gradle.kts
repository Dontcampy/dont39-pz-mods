import buildsrc.convention.check.CheckGlue
import buildsrc.convention.feature.GenerateFeatureInit
import buildsrc.convention.feature.MergeMedia
import buildsrc.convention.feature.PzArtifact
import buildsrc.convention.feature.jvmFolders
import buildsrc.convention.feature.pzIncoming
import buildsrc.convention.smoke.registerSmokeTest
import buildsrc.convention.widening.MergeClassTweakers

plugins {
  id("buildsrc.convention.pz-kotlin")
  id("buildsrc.convention.pz-mod")
}

version = "0.13.0"

// the bundled features, in init order
val features = listOf(
  ":mod-options-sidebar",
  ":ime-keyboard-fix",
  ":missing-anim-folders",
  ":anim-extends-case",
  ":tunnel",
  ":mod-sync",
  ":network-status",
  ":appearance-override",
)

val modId = pzMod.modId
val javaPkgName = pzMod.javaPkgName

val bundledFeatures = configurations.dependencyScope("bundledFeatures")
configurations.implementation { extendsFrom(bundledFeatures.get()) }
// code the features share: bundled like them, but no feature (no descriptor, init or glue)
val bundledLibs = configurations.dependencyScope("bundledLibs")

dependencies {
  features.forEach { bundledFeatures(project(it)) }
  bundledLibs(project(":allinone-common"))
  compileOnly(project(":zb-kt-utils"))
  compileOnly(project(":zabric"))
}

val featureClasses = jvmFolders("featureClasses", bundledFeatures, LibraryElements.CLASSES)
val featureResources = jvmFolders("featureResources", bundledFeatures, LibraryElements.RESOURCES)
val featureModFiles = pzIncoming("featureModFiles", bundledFeatures, PzArtifact.MOD_FILES)
val featureClassTweakers = pzIncoming("featureClassTweakers", bundledFeatures, PzArtifact.CLASSTWEAKER)
val featureInfo = pzIncoming("featureInfo", bundledFeatures, PzArtifact.FEATURE)
val libClasses = jvmFolders("libClasses", bundledLibs, LibraryElements.CLASSES)
val libModFiles = pzIncoming("libModFiles", bundledLibs, PzArtifact.MOD_FILES)

val generateFeatureInit = tasks.register<GenerateFeatureInit>("generateFeatureInit") {
  description = "Generates initFeatures(), which AllInOneMain calls."
  featureFolders.from(featureInfo)
  order = features.map { it.removePrefix(":") }
  packageName = javaPkgName
  outputDir = layout.buildDirectory.dir("generated/features")
}
kotlin.sourceSets.main { kotlin.srcDir(generateFeatureInit) }

// makes what the features' .classtweaker lists name public at game start
val mergeClassTweakers = tasks.register<MergeClassTweakers>("mergeClassTweakers") {
  description = "Merges the features' .classtweaker lists into one for this mod, so Zabric can read it at game start."
  lists.from(featureClassTweakers)
  merged = layout.buildDirectory.file("classtweaker/$modId.classtweaker")
}

tasks.jar {
  manifest { attributes("Zabric-Main" to "$javaPkgName.AllInOneMain") }
  from(featureClasses, featureResources, libClasses)
  // build data only (the features' source .classtweaker lists)
  exclude("META-INF/dont39/**")
  // where Zabric reads it at game start
  from(mergeClassTweakers) { into("META-INF/zabric") }
  duplicatesStrategy = DuplicatesStrategy.FAIL
}

val checkGlue = tasks.register<CheckGlue>("checkGlue") {
  featureFolders.from(featureInfo)
  modId = pzMod.modId
  javaPkgName = pzMod.javaPkgName
}
tasks.check { dependsOn(checkGlue) }

// this mod's own media files (the sandbox page's name) first, then the shared code's, then the features' in init order
val mergeMedia = tasks.register<MergeMedia>("mergeMedia") {
  description = "Merges this mod's, the shared code's and the features' media files into the mod's 42/media."
  folders.from("src/main/media", libModFiles, featureModFiles)
  merged = layout.buildDirectory.dir("media")
}

tasks.named<Sync>("assembleMod") {
  from(mergeMedia) { into("42/media") }
}

registerSmokeTest(kotlin = true) {
  featureFolders.from(featureInfo)
  // Lua-only features that gate on a target mod (directory-structure.md)
  luaTargeted = listOf()
}
