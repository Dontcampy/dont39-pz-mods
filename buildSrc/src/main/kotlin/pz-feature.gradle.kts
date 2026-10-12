package buildsrc.convention

// An All-in-One feature module: Kotlin (pz-kotlin), zombiebuddy-kotlin-utils, and the KSP processor that generates
// the feature's ZombieBuddy glue (patch classes, Lua bridge) from the annotations in zombiebuddy-kotlin-utils' glue
// package.
plugins {
  id("buildsrc.convention.pz-kotlin")
  id("com.google.devtools.ksp")
}

dependencies {
  implementation(project(":zombiebuddy-kotlin-utils"))
  ksp(project(":zombiebuddy-kotlin-utils-processor"))
}

ksp {
  // generated class names: viewpoint-blood-fx-fix -> ViewpointBloodFxFixPatches / ViewpointBloodFxFixLua
  arg("dont39.feature", project.name.split('-').joinToString("") { it.replaceFirstChar(Char::uppercaseChar) })
  // All-in-One's javaPkgName (dont39-all-in-one/mod/42/mod.info); ZombieBuddy only looks there
  arg("dont39.gluePackage", "com.dont39.pz.allinone")
  // Lua globals are shared by all mods (directory-structure.md "Naming")
  arg("dont39.luaPrefix", "Dont39AllInOne_")
}
