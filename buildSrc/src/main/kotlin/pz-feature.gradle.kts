package buildsrc.convention

import buildsrc.convention.feature.FEATURE_BUNDLE
import buildsrc.convention.feature.FeatureInfo
import buildsrc.convention.mod.PzGlue
import buildsrc.convention.mod.modInfoOf
import buildsrc.convention.widening.PzWidening

// A Kotlin feature module, bundled by dont39-all-in-one.
plugins {
  id("buildsrc.convention.pz-kotlin-glue")
  id("buildsrc.convention.pz-bundled-feature")
  id("buildsrc.convention.pz-widening")
}

dependencies {
  // shared with the other features, in the bundling mod's jar
  compileOnly(project(":allinone-common"))
  // the tests run outside that jar
  testImplementation(project(":allinone-common"))
}

// read as a file, not from the bundling mod's project
val bundle = modInfoOf(FEATURE_BUNDLE)
val pzGlue = the<PzGlue>()
pzGlue.gluePackage.convention(bundle.javaPkgName)
pzGlue.luaPrefix.convention("${bundle.id}_")
pzGlue.modId.convention(bundle.id)

// the bundling mod's generated initFeatures() calls this facade, so a facade of another name fails its compile
tasks.named<WriteProperties>("featureDescriptor") {
  property(FeatureInfo.FACADE, "com.dont39.pz.${project.name.replace("-", "")}.${pzGlue.stem}")
}
tasks.named<Sync>("stageFeatureInfo") {
  from(tasks.processResources) { include("${FeatureInfo.HOOKS}**", "${FeatureInfo.LUA}/**") }
}

// the bundling mod merges the features' lists into the one it ships
val pzWidening = the<PzWidening>()
if (pzWidening.listed) {
  configurations.named("pzClassTweakerElements") { outgoing.artifact(pzWidening.expanded) }
}
