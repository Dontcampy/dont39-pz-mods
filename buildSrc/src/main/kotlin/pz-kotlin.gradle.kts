package buildsrc.convention

import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

// Kotlin mods run on the Kotlin runtime the ZombieBuddyKotlin mod installs at startup, so kotlin/kotlinx are compile-only
// and never end up in a dependent mod's jar. kotlin.stdlib.default.dependency=false (root gradle.properties) stops the
// Kotlin plugin from adding the stdlib as an implementation dependency.
plugins {
  id("buildsrc.convention.pz-java")
  kotlin("jvm")
  kotlin("plugin.serialization")
}

kotlin {
  jvmToolchain(25)
  compilerOptions {
    // same level as pz-java's options.release
    jvmTarget = JvmTarget.JVM_17
    freeCompilerArgs.add("-Xjdk-release=17")
  }
}

val libs = the<VersionCatalogsExtension>().named("libs")

dependencies {
  listOf("kotlinStdlib", "kotlinxCoroutines", "kotlinxSerialization").forEach {
    compileOnly(libs.findLibrary(it).get())
  }
}

// projectzomboid.jar also contains the game's kotlin-stdlib 1.4.10 and the first copy on the classpath wins, so it goes
// last
val gameJar = providers.gradleProperty("pzDir").map { file("$it/projectzomboid.jar") }

tasks.withType<KotlinCompile>().configureEach {
  val game = gameJar.get()
  val classpath = files(libraries.from.toList())
  libraries.setFrom(classpath.filter { it != game }, game)
}
