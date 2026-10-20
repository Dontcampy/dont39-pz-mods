plugins {
  id("buildsrc.convention.pz-feature")
  id("buildsrc.convention.pz-junit")
}

dependencies {
  // compile-only for the mod (ZabricKotlin ships them), but the tests run outside the game
  testImplementation(libs.kotlinStdlib)
  testImplementation(libs.kotlinxSerialization)
}
