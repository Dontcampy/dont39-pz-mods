import buildsrc.convention.CheckBootstrapClasses
import buildsrc.convention.CheckLoaderRefs

// Java (and Lua) only: ZombieBuddy runs PreMain before ZombieBuddyKotlin has installed the Kotlin runtime.
plugins {
  id("buildsrc.convention.pz-java")
  id("buildsrc.convention.pz-mod")
  id("buildsrc.convention.pz-publish")
}

version = libs.versions.zabric.get()

// The published jar is the mod jar: authors compile against it, players get it from the Workshop item. The game and
// ZombieBuddy jars stay off the POM; authors have both locally.
mavenPublishing {
  pom {
    name = "Zabric"
    description = "Loader layer for ZombieBuddy Java mods for Project Zomboid: hook API, entrypoints and access " +
      "widening. Use it compileOnly with com.pzlauncher:zabric-processor as annotation processor."
  }
}

// the .classtweaker parser, shared with buildSrc
sourceSets.main {
  java.srcDir("src/classtweaker/java")
}

val checkBootstrapClasses = tasks.register<CheckBootstrapClasses>("checkBootstrapClasses") {
  description = "Fails if a class of this module references a kotlin/ type."
  classes.from(tasks.compileJava.flatMap { it.destinationDirectory })
}

tasks.check { dependsOn(checkBootstrapClasses) }

// The weave test applies glue through ZombieBuddy's own patch engine, which needs the game's classes to load.
val zombieBuddy = configurations.named("zombieBuddy")
configurations.testCompileClasspath { extendsFrom(zombieBuddy.get()) }
configurations.testRuntimeClasspath { extendsFrom(zombieBuddy.get()) }
val pzGameJar = extra["pzGameJar"] as RegularFileProperty

dependencies {
  testImplementation(platform(libs.junitBom))
  testImplementation(libs.junitJupiter)
  testRuntimeOnly(libs.junitPlatformLauncher)
  testRuntimeOnly(files(pzGameJar))
}

tasks.test {
  useJUnitPlatform()
  // the weave test attaches ByteBuddy's agent to its own JVM for an Instrumentation, as ZombieBuddy has at game start
  jvmArgs("-Djdk.attach.allowAttachSelf=true", "-XX:+EnableDynamicAgentLoading")
}

// the one hand-written place that uses ZombieBuddy's types
tasks.named<CheckLoaderRefs>("checkLoaderRefs") {
  excludes.add("com/pzlauncher/zabric/loader/**")
}

tasks.jar {
  dependsOn(checkBootstrapClasses)
  // ZombieBuddy also needs javaPreload=true in mod.info, so a mod.info edit alone cannot make a jar preload
  manifest { attributes("ZB-Preload" to "true") }
}

tasks.named<Sync>("assembleMod") {
  // no client/ or server/ in the path: loads on both (ZombieBuddy ModdingGuide, "Client/server JAR paths")
  from(tasks.jar) { into("42/media/java") }
  from("src/main/lua") { into("42/media/lua") }
}
