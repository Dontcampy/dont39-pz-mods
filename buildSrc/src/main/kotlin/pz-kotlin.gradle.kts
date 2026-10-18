package buildsrc.convention

import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

// Kotlin mods run on the Kotlin runtime the ZabricKotlin mod installs at startup, so kotlin/kotlinx are compile-only
// and never end up in a dependent mod's jar. kotlin.stdlib.default.dependency=false (root gradle.properties) stops the
// Kotlin plugin from adding the stdlib as an implementation dependency.
plugins {
  id("buildsrc.convention.pz-java")
  kotlin("jvm")
  kotlin("plugin.serialization")
}

// the toolchain comes from pz-jvm's java extension
kotlin {
  compilerOptions {
    jvmTarget = JvmTarget.fromTarget("$PZ_RELEASE")
    freeCompilerArgs.add("-Xjdk-release=$PZ_RELEASE")
  }
}

dependencies {
  listOf("kotlinStdlib", "kotlinxCoroutines", "kotlinxSerialization").forEach {
    compileOnly(catalogLibrary(it))
  }
}

// projectzomboid.jar also contains the game's kotlin-stdlib 1.4.10 and the first copy on the classpath wins, so it goes
// last
val gameJar = the<PzGame>().gameJar.map { it.asFile }

tasks.withType<KotlinCompile>().configureEach {
  val game = gameJar
  val classpath = libraries
  libraries.setFrom(files(libraries.from.toList()).filter { it != game.get() }, game)
  // another plugin may append after the reorder (KSP does, harmlessly)
  doFirst {
    val entries = classpath.files.toList()
    val shadowed = entries.drop(entries.indexOf(game.get()) + 1).filter(::hasKotlinClasses)
    if (shadowed.isNotEmpty()) {
      throw GradleException("$path: the game's Kotlin in ${game.get().name} would shadow Kotlin classes of $shadowed")
    }
  }
}
