import buildsrc.convention.check.CheckJdkGlue
import buildsrc.convention.check.CheckLoaderRefs
import buildsrc.convention.mod.PzGame
import buildsrc.convention.mod.modInfoOf
import buildsrc.convention.mod.pzDir
import buildsrc.convention.mod.pzUserDir
import buildsrc.convention.mod.pzWorkshopContent
import buildsrc.convention.smoke.GameSmokeTest

// Java only: Zabric opens its hooks at game start, before ZabricKotlin could install Kotlin.
plugins {
  id("buildsrc.convention.pz-java")
  id("buildsrc.convention.pz-mod")
  id("buildsrc.convention.pz-bootstrap")
  id("buildsrc.convention.pz-widening")
  id("buildsrc.convention.pz-junit")
}

version = "0.2.0"

val modId = pzMod.modId
val pkg = pzMod.javaPkgName
val pkgDir = pkg.replace('.', '/')

// client only; whether ZombieBuddy then skips it on a dedicated server varies, so Session checks GameServer.server too
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
      // server joins load the server's mod list, which lacks a client-only mod
      "Zabric-Hooks" to "launch",
    )
  }
  // build data only (the source list)
  exclude("META-INF/dont39/**")
  // where Zabric reads it at game start; no fix reaches a non-public member yet
  if (pzWidening.listed) {
    val listName = "$modId.classtweaker"
    from(pzWidening.expanded) {
      into("META-INF/zabric")
      rename { listName }
    }
  }
}

// the game's own method is the comment stripping test's reference
configurations.testCompileClasspath { extendsFrom(configurations.named("pzCompileOnly").get()) }
configurations.testRuntimeClasspath { extendsFrom(configurations.named("pzCompileOnly").get()) }

val gameJar = the<PzGame>().gameJar
tasks.test {
  // the real files the comment stripping test compares on; the Workshop folder is too large to track as an input
  val gameDir = pzDir()
  val workshop = pzWorkshopContent()
  inputs.file(gameJar).withPropertyName("gameJar").withPathSensitivity(PathSensitivity.NONE)
  // the encoder tests run stb_dxt from the game's LWJGL
  jvmArgs("--enable-native-access=ALL-UNNAMED")
  doFirst {
    val test = this as Test
    test.systemProperty("fasterloading.gameDir", gameDir.get().asFile.absolutePath)
    test.systemProperty("fasterloading.workshop", workshop.get().asFile.absolutePath)
  }
}

// needs a GPU and the sample packs installed, so not part of check
tasks.register<JavaExec>("qualityCheck") {
  description = "Compares the texture encoder's image quality with the graphics driver's on real tile pages."
  group = "verification"
  classpath = sourceSets.test.get().runtimeClasspath
  mainClass = "com.dont39.pz.fasterloading.textures.QualityCheck"
  jvmArgs("--enable-native-access=ALL-UNNAMED")
  val gameDir = pzDir()
  val workshop = pzWorkshopContent()
  argumentProviders.add(CommandLineArgumentProvider {
    listOf(gameDir.get().asFile.absolutePath, workshop.get().asFile.absolutePath)
  })
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
  // no fix needs a widened member yet
  widener = false
}
