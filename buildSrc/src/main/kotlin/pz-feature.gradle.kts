package buildsrc.convention

// An All-in-One feature module.
plugins {
  id("buildsrc.convention.pz-kotlin")
  id("com.google.devtools.ksp")
}

dependencies {
  implementation(project(":zombiebuddy-kotlin-utils"))
  ksp(project(":zombiebuddy-kotlin-utils-processor"))
}

ksp {
  arg("dont39.feature", project.name.split('-').joinToString("") { it.replaceFirstChar(Char::uppercaseChar) })
  // All-in-One's javaPkgName; ZombieBuddy only looks there
  arg("dont39.gluePackage", "com.dont39.pz.allinone")
  // Lua globals are shared by all mods
  arg("dont39.luaPrefix", "Dont39AllInOne_")
}
