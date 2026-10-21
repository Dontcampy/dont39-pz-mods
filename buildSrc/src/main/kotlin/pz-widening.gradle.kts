package buildsrc.convention

import buildsrc.convention.mod.PZ_GAME_JAR
import buildsrc.convention.mod.PzGame
import buildsrc.convention.mod.pzDir
import buildsrc.convention.mod.pzWorkshopContent
import buildsrc.convention.widening.PzTargets
import buildsrc.convention.widening.PzWidening
import buildsrc.convention.widening.WidenJars

// A module that compiles against target mods (pzTargets) or non-public members its .classtweaker lists (pzWidening).
plugins {
  id("buildsrc.convention.pz-java")
}

val pzGame = the<PzGame>()
val pzTargets = extensions.create<PzTargets>("pzTargets", pzWorkshopContent())

// Members the module reaches directly although they are not public; the same list is applied at game start.
val classTweaker = layout.projectDirectory.file("src/main/resources/META-INF/dont39/${project.name}.classtweaker")
val pzWidening = extensions.create<PzWidening>("pzWidening", classTweaker.asFile.exists())
if (pzWidening.listed) {
  val widen = tasks.register<WidenJars>("widenCompileJars") {
    description = "Widens the jars ${project.name} compiles against as its .classtweaker lists."
    tweakers.from(classTweaker)
    jars.from(pzDir().map { it.file(PZ_GAME_JAR) }, pzTargets.jars)
    widened = layout.buildDirectory.dir("classtweaker/jars")
    expanded = layout.buildDirectory.file("classtweaker/${project.name}.classtweaker")
  }
  pzGame.gameJar.set(widen.flatMap { it.widened.file(PZ_GAME_JAR) })
  pzWidening.expanded.set(widen.flatMap { it.expanded })
  val widenedTargets = files(widen.flatMap { it.widened }).asFileTree.matching { exclude(PZ_GAME_JAR) }
  dependencies { "pzCompileOnly"(widenedTargets) }
} else {
  dependencies { "pzCompileOnly"(pzTargets.jars) }
}
