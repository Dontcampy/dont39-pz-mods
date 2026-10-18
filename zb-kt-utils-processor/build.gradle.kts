import buildsrc.convention.PZ_TOOLCHAIN

// Runs inside the Kotlin compiler, never in the game: a plain JVM module, not bundled.
plugins {
  kotlin("jvm")
}

// not pz-jvm: its Java release level would differ from this Kotlin-only module's target
kotlin {
  jvmToolchain(PZ_TOOLCHAIN)
}

dependencies {
  implementation(libs.kspApi)
  // Zabric's hook glue generator, shared with its javac front end
  implementation(project(":zabric-processor"))
  // the root gradle.properties turns off the Kotlin plugin's default stdlib dependency
  implementation(libs.kotlinStdlib)
}
