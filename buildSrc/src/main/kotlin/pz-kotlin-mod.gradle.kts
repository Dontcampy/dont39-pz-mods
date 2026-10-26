package buildsrc.convention

import buildsrc.convention.check.CheckLoaderRefs
import buildsrc.convention.feature.PzArtifact
import buildsrc.convention.feature.pzIncoming
import buildsrc.convention.mod.PzMod
import buildsrc.convention.mod.kotlinVersions
import buildsrc.convention.mod.modInfoOf
import buildsrc.convention.mod.pzDir
import buildsrc.convention.mod.pzUserDir
import buildsrc.convention.smoke.GameSmokeTest
import buildsrc.convention.widening.PzWidening

// A Kotlin mod built from one module, with its own mod folder; the counterpart of pz-feature, whose features
// dont39-all-in-one bundles.
plugins {
  id("buildsrc.convention.pz-kotlin")
  id("com.google.devtools.ksp")
  id("buildsrc.convention.pz-mod")
  id("buildsrc.convention.pz-widening")
}

val pzMod = the<PzMod>()
val modId = pzMod.modId
val pkg = pzMod.javaPkgName
val pkgDir = pkg.replace('.', '/')
// server-reborn: ServerReborn, the stem of the generated glue, the Lua bridge and the main entrypoint
val modName = project.name.split('-').joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }

// ZabricKotlin, whose version the smokeTest checks
val kotlinLib = configurations.dependencyScope("kotlinLib")

dependencies {
  // ships in ZabricKotlin's jar
  compileOnly(project(":zb-kt-utils"))
  // hook annotations and the runtime the generated glue calls
  compileOnly(project(":zabric"))
  ksp(project(":zb-kt-utils-processor"))
  kotlinLib(project(":zb-kt"))
}

ksp {
  arg("dont39.feature", modName)
  arg("dont39.gluePackage", pkg)
  arg("dont39.luaPrefix", "${modId}_")
  arg("zabric.modId", modId)
  arg("zabric.glueClass", "${modName}Glue")
}

// the generated glue is ZombieBuddy code
tasks.named<CheckLoaderRefs>("checkLoaderRefs") {
  excludes.add("$pkgDir/${modName}Glue*")
}

val pzWidening = the<PzWidening>()
tasks.jar {
  manifest {
    attributes("Zabric-Main" to "$pkg.${modName}Main")
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

val kotlinLibFolder = pzIncoming("kotlinLibFolder", kotlinLib, PzArtifact.MOD_FOLDER)

tasks.register<GameSmokeTest>("smokeTest") {
  description = "Launches Project Zomboid until ZombieBuddy has loaded $modId, then checks the log."
  dependsOn(tasks.named("deployWorkshop"), ":zb-kt:deployWorkshop", ":zabric:deployWorkshop")
  gameDir = pzDir()
  console = pzUserDir().map { it.file("console.txt") }
  this.modId = modId
  version = project.version.toString()
  zabricId = modInfoOf("zabric").id
  kotlinMod.from(kotlinLibFolder)
  kotlinVersions = kotlinVersions()
  hooks = true
  widener = pzWidening.listed
}
