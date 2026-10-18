import buildsrc.convention.PZ_TOOLCHAIN
import buildsrc.convention.ZOMBIEBUDDY_JAR
import buildsrc.convention.pzDir

// Runs inside the Kotlin compiler, never in the game: a plain JVM module, not bundled.
plugins {
  kotlin("jvm")
  id("buildsrc.convention.pz-junit")
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
  testImplementation(libs.kctforkKsp)
  // what the test compilations compile against: Zabric's hook API, the Feature and @LuaGlobal of zb-kt-utils, and
  // ZombieBuddy's types the generated glue names
  testRuntimeOnly(project(":zabric"))
  testRuntimeOnly(project(":zb-kt-utils"))
  testRuntimeOnly(files(pzDir().map { it.file(ZOMBIEBUDDY_JAR) }))
}
