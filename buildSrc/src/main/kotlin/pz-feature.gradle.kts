package buildsrc.convention

import buildsrc.convention.check.CheckLoaderRefs
import buildsrc.convention.feature.FEATURE_BUNDLE
import buildsrc.convention.feature.FeatureInfo
import buildsrc.convention.feature.PzGlue
import buildsrc.convention.mod.modInfoOf
import buildsrc.convention.widening.PzWidening

// A Kotlin feature module, bundled by dont39-all-in-one.
plugins {
  id("buildsrc.convention.pz-kotlin")
  id("com.google.devtools.ksp")
  id("buildsrc.convention.pz-bundled-feature")
  id("buildsrc.convention.pz-widening")
}

dependencies {
  // ships in ZabricKotlin's jar
  compileOnly(project(":zb-kt-utils"))
  // hook annotations and the runtime the generated glue calls
  compileOnly(project(":zabric"))
  ksp(project(":zb-kt-utils-processor"))
  // shared with the other features, in the bundling mod's jar
  compileOnly(project(":allinone-common"))
  // the tests run outside that jar
  testImplementation(project(":allinone-common"))
}

// read as a file, not from the bundling mod's project
val bundle = modInfoOf(FEATURE_BUNDLE)
val pzGlue = extensions.create<PzGlue>("pzGlue")
pzGlue.gluePackage.convention(bundle.javaPkgName)
pzGlue.luaPrefix.convention("${bundle.id}_")
pzGlue.modId.convention(bundle.id)

val featureName = project.name.split('-').joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }

ksp {
  arg("dont39.feature", featureName)
  arg("dont39.gluePackage", pzGlue.gluePackage)
  arg("dont39.luaPrefix", pzGlue.luaPrefix)
  arg("zabric.modId", pzGlue.modId)
  // each feature is its own compilation inside the bundling mod, so each needs its own glue class
  arg("zabric.glueClass", "${featureName}Glue")
}

// the generated glue is ZombieBuddy code
tasks.named<CheckLoaderRefs>("checkLoaderRefs") {
  excludes.add(pzGlue.gluePackage.map { it.replace('.', '/') + "/*" })
}

// the bundling mod's generated initFeatures() calls this facade, so a facade of another name fails its compile
tasks.named<WriteProperties>("featureDescriptor") {
  property(FeatureInfo.FACADE, "com.dont39.pz.${project.name.replace("-", "")}.$featureName")
}
tasks.named<Sync>("stageFeatureInfo") {
  from(tasks.processResources) { include("${FeatureInfo.HOOKS}**", "${FeatureInfo.LUA}/**") }
}

// the bundling mod merges the features' lists into the one it ships
val pzWidening = the<PzWidening>()
if (pzWidening.listed) {
  configurations.named("pzClassTweakerElements") { outgoing.artifact(pzWidening.expanded) }
}
