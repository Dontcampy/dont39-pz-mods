// Code the All-in-One features share, in Dont39AllInOne's jar only. No feature: no facade, hooks or glue.
plugins {
  id("buildsrc.convention.pz-kotlin")
  id("buildsrc.convention.pz-bundled")
  id("buildsrc.convention.pz-junit")
}

dependencies {
  // compile-only for the mod (ZabricKotlin ships it), but the tests run outside the game
  testImplementation(libs.kotlinStdlib)
}
