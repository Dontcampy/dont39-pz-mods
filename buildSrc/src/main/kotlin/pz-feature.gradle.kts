package buildsrc.convention

import buildsrc.convention.check.CheckLoaderRefs
import buildsrc.convention.feature.FEATURE_BUNDLE
import buildsrc.convention.feature.FeatureInfo
import buildsrc.convention.feature.PzGlue
import buildsrc.convention.mod.PZ_GAME_JAR
import buildsrc.convention.mod.PzGame
import buildsrc.convention.mod.modInfoOf
import buildsrc.convention.mod.pzDir
import buildsrc.convention.mod.pzWorkshopContent
import buildsrc.convention.widening.PzTargets
import buildsrc.convention.widening.WidenJars

// A Kotlin feature module, bundled by dont39-all-in-one.
plugins {
  id("buildsrc.convention.pz-kotlin")
  id("com.google.devtools.ksp")
  id("buildsrc.convention.pz-bundled")
}

dependencies {
  // ships in ZabricKotlin's jar
  compileOnly(project(":zb-kt-utils"))
  // hook annotations and the runtime the generated glue calls
  compileOnly(project(":zabric"))
  ksp(project(":zb-kt-utils-processor"))
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

val pzGame = the<PzGame>()
val pzTargets = extensions.create<PzTargets>("pzTargets", pzWorkshopContent())

// Members the feature reaches directly although they are not public; the same list is applied at game start.
val classTweaker = layout.projectDirectory.file("src/main/resources/META-INF/dont39/${project.name}.classtweaker")
if (classTweaker.asFile.exists()) {
  val widen = tasks.register<WidenJars>("widenCompileJars") {
    description = "Widens the jars ${project.name} compiles against as its .classtweaker lists."
    tweakers.from(classTweaker)
    jars.from(pzDir().map { it.file(PZ_GAME_JAR) }, pzTargets.jars)
    widened = layout.buildDirectory.dir("classtweaker/jars")
    expanded = layout.buildDirectory.file("classtweaker/${project.name}.classtweaker")
  }
  pzGame.gameJar.set(widen.flatMap { it.widened.file(PZ_GAME_JAR) })
  val widenedTargets = files(widen.flatMap { it.widened }).asFileTree.matching { exclude(PZ_GAME_JAR) }
  dependencies { "pzCompileOnly"(widenedTargets) }
  configurations.named("pzClassTweakerElements") { outgoing.artifact(widen.flatMap { it.expanded }) }
} else {
  dependencies { "pzCompileOnly"(pzTargets.jars) }
}
