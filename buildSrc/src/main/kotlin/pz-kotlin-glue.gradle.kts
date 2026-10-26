package buildsrc.convention

import buildsrc.convention.check.CheckLoaderRefs
import buildsrc.convention.mod.PzGlue

// A Kotlin module with hooks and Lua functions, whose glue the KSP front end generates: an All-in-One feature
// (pz-feature) or a mod of its own (pz-kotlin-mod), which set where the glue goes (pzGlue).
plugins {
  id("buildsrc.convention.pz-kotlin")
  id("com.google.devtools.ksp")
}

val pzGlue = extensions.create<PzGlue>(
  "pzGlue",
  project.name.split('-').joinToString("") { it.replaceFirstChar(Char::uppercaseChar) },
)

dependencies {
  // ships in ZabricKotlin's jar
  compileOnly(project(":zb-kt-utils"))
  // hook annotations and the runtime the generated glue calls
  compileOnly(project(":zabric"))
  ksp(project(":zb-kt-utils-processor"))
}

ksp {
  arg("dont39.feature", pzGlue.stem)
  arg("dont39.gluePackage", pzGlue.gluePackage)
  arg("dont39.luaPrefix", pzGlue.luaPrefix)
  arg("zabric.modId", pzGlue.modId)
  // features share their bundling mod's package, each in its own compilation, so each needs its own glue class
  arg("zabric.glueClass", "${pzGlue.stem}Glue")
}

// the generated glue is ZombieBuddy code
tasks.named<CheckLoaderRefs>("checkLoaderRefs") {
  val glueClass = "${pzGlue.stem}Glue"
  excludes.add(pzGlue.gluePackage.map { "${it.replace('.', '/')}/$glueClass*" })
}
