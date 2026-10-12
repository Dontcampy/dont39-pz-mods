// KSP processor that generates the ZombieBuddy glue (patch classes, Lua bridge) of each feature module from the
// annotations in zombiebuddy-kotlin-utils' glue package. Runs inside the Kotlin compiler, never in the game, so it is a
// plain JVM module (not pz-java / pz-kotlin) and is not bundled. Wired into features by pz-feature.
plugins {
  kotlin("jvm")
}

kotlin {
  jvmToolchain(25)
}

dependencies {
  implementation(libs.kspApi)
  // the root gradle.properties turns off the Kotlin plugin's default stdlib dependency
  implementation(libs.kotlinStdlib)
}
