// The runtime Kotlin mods build on; shipped in ZabricKotlin's jar (zb-kt), compile-only everywhere else.
plugins {
  id("buildsrc.convention.pz-kotlin")
}

dependencies {
  // ZabricKotlin requires Zabric
  compileOnly(project(":zabric"))
}
