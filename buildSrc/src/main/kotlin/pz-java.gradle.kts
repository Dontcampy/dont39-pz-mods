package buildsrc.convention

import buildsrc.convention.check.CheckLoaderRefs
import buildsrc.convention.mod.PZ_GAME_JAR
import buildsrc.convention.mod.PzGame
import buildsrc.convention.mod.ZOMBIEBUDDY_JAR
import buildsrc.convention.mod.pzDir

plugins {
  id("buildsrc.convention.pz-jvm")
  `java-library`
  id("io.github.zed-0xff.zb-gradle-plugin")
}

// Signs the mod jar (off for now).
zbSigning {
  enabled = false
}

val pzGame = extensions.create<PzGame>("pzGame")
pzGame.gameJar.convention(pzDir().map { it.file(PZ_GAME_JAR) })

// Not on compileOnly, which zb-gradle-plugin 1.0.3 reads at configuration time: it would add ZombieBuddy.jar as
// annotation processor (it has none) and a compileJava doFirst that holds the Project, which the configuration cache
// cannot store, and it would need pzDir to configure.
val zombieBuddy = configurations.dependencyScope("zombieBuddy")
// the game jar and target-mod jars (pz-feature), provided at runtime
val pzCompileOnly = configurations.dependencyScope("pzCompileOnly")
configurations.compileClasspath { extendsFrom(zombieBuddy.get(), pzCompileOnly.get()) }

dependencies {
  zombieBuddy(files(pzDir().map { it.file(ZOMBIEBUDDY_JAR) }))
  pzCompileOnly(files(pzGame.gameJar))
}

// Keeps loader types (ZombieBuddy) in Zabric's loader package and generated glue, which exclude themselves.
val checkLoaderRefs = tasks.register<CheckLoaderRefs>("checkLoaderRefs") {
  description = "Fails if a class of this module references a ZombieBuddy type."
  classes.from(sourceSets.main.map { it.output.classesDirs })
}

tasks.check { dependsOn(checkLoaderRefs) }
