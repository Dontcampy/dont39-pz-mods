package buildsrc.convention

import buildsrc.convention.check.CheckEntrypoint
import buildsrc.convention.mod.PzGlue
import buildsrc.convention.mod.PzMod
import buildsrc.convention.smoke.registerSmokeTest
import buildsrc.convention.widening.PzWidening

// A Kotlin mod built from one module, with its own mod folder; the counterpart of pz-feature, whose features
// dont39-all-in-one bundles.
plugins {
  id("buildsrc.convention.pz-kotlin-glue")
  id("buildsrc.convention.pz-mod")
  id("buildsrc.convention.pz-widening")
}

val pzMod = the<PzMod>()
val modId = pzMod.modId
val pkg = pzMod.javaPkgName

val pzGlue = the<PzGlue>()
pzGlue.gluePackage.convention(pkg)
pzGlue.luaPrefix.convention("${modId}_")
pzGlue.modId.convention(modId)

// server-reborn: ServerReborn, the stem of the generated glue, the Lua bridge and the main entrypoint
val mainClass = "$pkg.${pzGlue.stem}Main"

val checkMainEntrypoint = tasks.register<CheckEntrypoint>("checkMainEntrypoint") {
  description = "Fails if the class the jar's manifest names as Zabric-Main was not compiled."
  classes.from(sourceSets.main.map { it.output.classesDirs })
  attribute = "Zabric-Main"
  className = mainClass
  marker = layout.buildDirectory.file("checks/$name.txt")
}
tasks.check { dependsOn(checkMainEntrypoint) }

val pzWidening = the<PzWidening>()
tasks.jar {
  dependsOn(checkMainEntrypoint)
  manifest {
    attributes("Zabric-Main" to mainClass)
  }
  // build data only (the source list)
  exclude("META-INF/dont39/**")
  if (pzWidening.listed) {
    // where Zabric reads it at game start; a local, as the configuration cache cannot keep a script reference
    val listName = "$modId.classtweaker"
    from(pzWidening.expanded) {
      into("META-INF/zabric")
      rename { listName }
    }
  }
}

tasks.named<Sync>("assembleMod") {
  from("src/main/lua") { into("42/media/lua") }
  // other media files, e.g. sandbox-options.txt
  from("src/main/media") { into("42/media") }
}

registerSmokeTest(kotlin = true) {
  hooks = true
  widener = pzWidening.listed
}
