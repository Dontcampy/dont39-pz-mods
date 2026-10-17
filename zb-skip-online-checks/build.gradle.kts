import buildsrc.convention.CheckBootstrapClasses
import buildsrc.convention.CheckLoaderRefs

// Java only: ZombieBuddy can run PreMain from the game folder before any mod, ZabricKotlin included, is loaded.
plugins {
  id("buildsrc.convention.pz-java")
  id("buildsrc.convention.pz-mod")
}

version = "0.1.0"

dependencies {
  compileOnly(project(":zabric"))
}

val checkBootstrapClasses = tasks.register<CheckBootstrapClasses>("checkBootstrapClasses") {
  description = "Fails if a class of this module references a kotlin/ type."
  classes.from(tasks.compileJava.flatMap { it.destinationDirectory })
}

tasks.check { dependsOn(checkBootstrapClasses) }

// a mod whose target is the loader itself; the entrypoint subpackage stays checked
tasks.named<CheckLoaderRefs>("checkLoaderRefs") {
  excludes.add("com/dont39/pz/zbskiponlinechecks/*.class")
}

val modId = extra["modId"] as String
val pkg = "com.dont39.pz.zbskiponlinechecks"

tasks.jar {
  dependsOn(checkBootstrapClasses)
  manifest {
    attributes(
      // ZombieBuddy also needs javaPreload=true in mod.info
      "ZB-Preload" to "true",
      "Zabric-PreLaunch" to "$pkg.entrypoint.PreLaunchEntry",
      "Zabric-Main" to "$pkg.entrypoint.MainEntry",
    )
  }
}

tasks.named<Sync>("assembleMod") {
  // no client/ or server/ in the path: loads on both (ZombieBuddy ModdingGuide, "Client/server JAR paths")
  from(tasks.jar) { into("42/media/java") }
}

// The vmArgs entry that loads this copy is a manual step (README.md): Steam rewrites ProjectZomboid64.json.
tasks.register("deployAgentJar") {
  group = "mod"
  description = "Copies the $modId jar into the game folder for ZombieBuddy's patches_jar option."
  val jar = tasks.jar.flatMap { it.archiveFile }
  // the one file, not the game folder: other tasks read the game's jars from there
  val jarName = "$modId.jar"
  val target = providers.gradleProperty("pzDir").map { File(it, jarName) }
  inputs.file(jar)
  outputs.file(target)
  doLast {
    jar.get().asFile.copyTo(target.get(), overwrite = true)
  }
}

// The test installs the advice through ZombieBuddy's Instrumentation field, as at game start.
val zombieBuddy = configurations.named("zombieBuddy")
configurations.testCompileClasspath { extendsFrom(zombieBuddy.get()) }
configurations.testRuntimeClasspath { extendsFrom(zombieBuddy.get()) }

dependencies {
  testImplementation(platform(libs.junitBom))
  testImplementation(libs.junitJupiter)
  testRuntimeOnly(libs.junitPlatformLauncher)
}

tasks.test {
  useJUnitPlatform()
  // ByteBuddy's agent attaches to the test JVM for an Instrumentation, as ZombieBuddy has at game start
  jvmArgs("-Djdk.attach.allowAttachSelf=true", "-XX:+EnableDynamicAgentLoading")
}
