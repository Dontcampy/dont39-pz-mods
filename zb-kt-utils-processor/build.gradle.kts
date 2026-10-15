// Runs inside the Kotlin compiler, never in the game: a plain JVM module, not bundled.
plugins {
  kotlin("jvm")
}

kotlin {
  jvmToolchain(25)
}

dependencies {
  implementation(libs.kspApi)
  // Zabric's hook glue generator, shared with its javac front end
  implementation(project(":zabric-processor"))
  // the root gradle.properties turns off the Kotlin plugin's default stdlib dependency
  implementation(libs.kotlinStdlib)
}
