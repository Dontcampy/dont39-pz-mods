// The runtime Kotlin mods build on; shipped in ZombieBuddyKotlin's jar (zb-kt), compile-only everywhere else.
plugins {
  id("buildsrc.convention.pz-kotlin")
}

dependencies {
  // ZombieBuddyKotlin requires Zabric
  compileOnly(project(":zabric"))
}
