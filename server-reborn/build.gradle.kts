plugins {
  id("buildsrc.convention.pz-kotlin-mod")
  id("buildsrc.convention.pz-junit")
}

version = "0.1.0"

dependencies {
  // compile-only for the mod (ZabricKotlin ships it), but the tests run outside the game
  testImplementation(libs.kotlinStdlib)
}
