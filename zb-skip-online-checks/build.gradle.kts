import buildsrc.convention.CheckLoaderRefs
import buildsrc.convention.pzDir

// Java only: ZombieBuddy can run PreMain from the game folder before any mod, ZabricKotlin included, is loaded.
plugins {
  id("buildsrc.convention.pz-java")
  id("buildsrc.convention.pz-mod")
  id("buildsrc.convention.pz-bootstrap")
  id("buildsrc.convention.pz-loader-test")
}

version = "0.1.0"

dependencies {
  compileOnly(project(":zabric"))
}

val modId = pzMod.modId
val pkg = pzMod.javaPkgName

// a mod whose target is the loader itself; the entrypoint subpackage stays checked
tasks.named<CheckLoaderRefs>("checkLoaderRefs") {
  excludes.add("${pkg.replace('.', '/')}/*.class")
}

tasks.jar {
  manifest {
    attributes(
      // ZombieBuddy also needs javaPreload=true in mod.info
      "ZB-Preload" to "true",
      "Zabric-PreLaunch" to "$pkg.entrypoint.PreLaunchEntry",
      "Zabric-Main" to "$pkg.entrypoint.MainEntry",
    )
  }
}

// The vmArgs entry that loads this copy is a manual step (README.md): Steam rewrites ProjectZomboid64.json.
tasks.register("deployAgentJar") {
  group = "mod"
  description = "Copies the $modId jar into the game folder for ZombieBuddy's patches_jar option."
  val jar = tasks.jar.flatMap { it.archiveFile }
  // the one file, not the game folder: other tasks read the game's jars from there
  val jarName = "$modId.jar"
  val target = pzDir().map { it.file(jarName) }
  inputs.file(jar)
  outputs.file(target)
  doLast {
    jar.get().asFile.copyTo(target.get().asFile, overwrite = true)
  }
}
