import buildsrc.convention.check.CheckJdkGlue
import buildsrc.convention.check.CheckLoaderRefs
import buildsrc.convention.mod.modInfoOf
import buildsrc.convention.mod.pzDir
import buildsrc.convention.mod.pzUserDir
import buildsrc.convention.smoke.GameSmokeTest

// Java only: Zabric opens its hooks at game start, before ZabricKotlin could install Kotlin.
plugins {
  id("buildsrc.convention.pz-java")
  id("buildsrc.convention.pz-mod")
  id("buildsrc.convention.pz-bootstrap")
  id("buildsrc.convention.pz-widening")
  id("buildsrc.convention.pz-junit")
  id("buildsrc.convention.pz-switches")
}

version = "0.2.0"

val modId = pzMod.modId
val pkg = pzMod.javaPkgName
val pkgDir = pkg.replace('.', '/')

// client only; whether ZombieBuddy then skips it on a dedicated server varies, so Loading checks GameServer.server too
pzMod.jarFolder = "media/java/client"

dependencies {
  compileOnly(project(":zabric"))
  annotationProcessor(project(":zabric-processor"))
}

tasks.compileJava {
  options.compilerArgs.addAll(listOf("-Azabric.modId=$modId", "-Azabric.javaPkgName=$pkg"))
}

// the generated glue is ZombieBuddy code
tasks.named<CheckLoaderRefs>("checkLoaderRefs") {
  excludes.add("$pkgDir/*.class")
}

val checkJdkGlue = tasks.register<CheckJdkGlue>("checkJdkGlue") {
  description = "Fails if the generated glue names a type outside the JDK, which ZombieBuddy would load at premain."
  classes = tasks.compileJava.flatMap { it.destinationDirectory }
  gluePackage = pkgDir
}
tasks.check { dependsOn(checkJdkGlue) }

tasks.jar {
  dependsOn(checkJdkGlue)
  manifest {
    attributes(
      // ZombieBuddy also needs javaPreload=true in mod.info
      "ZB-Preload" to "true",
      "Zabric-PreLaunch" to "$pkg.entrypoint.PreLaunchEntry",
      "Zabric-Main" to "$pkg.entrypoint.MainEntry",
      // the bar covers server joins whatever their mod list
      "Zabric-Hooks" to "launch",
    )
  }
  // build data only (the source list)
  exclude("META-INF/dont39/**")
  // where Zabric reads it at game start
  val listName = "$modId.classtweaker"
  from(pzWidening.expanded) {
    into("META-INF/zabric")
    rename { listName }
  }
}

// the mod options page and its text
tasks.named<Sync>("assembleMod") {
  from("src/main/lua") { into("42/media/lua") }
}

tasks.register<GameSmokeTest>("smokeTest") {
  description = "Launches Project Zomboid until ZombieBuddy has loaded $modId, then checks the log."
  dependsOn(tasks.named("deployWorkshop"), ":zabric:deployWorkshop")
  gameDir = pzDir()
  console = pzUserDir().map { it.file("console.txt") }
  modId = pzMod.modId
  version = project.version.toString()
  zabricId = modInfoOf("zabric").id
  hooks = true
}
