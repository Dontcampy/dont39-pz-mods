import buildsrc.convention.check.CheckLoaderRefs

// Java (and Lua) only: ZombieBuddy runs PreMain before ZabricKotlin has installed the Kotlin runtime.
plugins {
  id("buildsrc.convention.pz-java")
  id("buildsrc.convention.pz-mod")
  id("buildsrc.convention.pz-bootstrap")
  id("buildsrc.convention.pz-loader-test")
  id("buildsrc.convention.pz-publish")
}

version = libs.versions.zabric.get()

// FFM is final in Java 22, but authors' builds read the jar at Zabric's release: only this code is newer, and the game
// runs on 25
val ffm = sourceSets.create("ffm")
tasks.named<JavaCompile>(ffm.compileJavaTaskName) { options.release = 22 }
sourceSets.main {
  compileClasspath += ffm.output
  runtimeClasspath += ffm.output
}
sourceSets.test {
  compileClasspath += ffm.output
  runtimeClasspath += ffm.output
}
tasks.jar { from(ffm.output) }
tasks.named<Jar>("sourcesJar") { from(ffm.allSource) }

tasks.test {
  // as the game's Windows launchers pass it, so FFM runs without a warning
  jvmArgs("--enable-native-access=ALL-UNNAMED")
}

// The published jar is the mod jar: authors compile against it, players get it from the Workshop item. The game and
// ZombieBuddy jars stay off the POM; authors have both locally.
mavenPublishing {
  pom {
    name = "Zabric"
    description = "Loader layer for ZombieBuddy Java mods for Project Zomboid: hook API, entrypoints and access " +
      "widening. Use it compileOnly with com.pzlauncher:zabric-processor as annotation processor."
  }
}

// the .classtweaker parser, shared with buildSrc; the hook listing format, shared with zabric-processor
sourceSets.main {
  java.srcDir("src/classtweaker/java")
  java.srcDir("src/listingformat/java")
}

dependencies {
  // the weave test applies glue through ZombieBuddy's own patch engine, which needs the game's classes to load
  testRuntimeOnly(files(pzGame.gameJar))
  // the Lua globals test exposes through Kahlua's own exposer
  testCompileOnly(files(pzGame.gameJar))
}

// the one hand-written place that uses ZombieBuddy's types
tasks.named<CheckLoaderRefs>("checkLoaderRefs") {
  excludes.add("com/pzlauncher/zabric/loader/**")
}

tasks.jar {
  // ZombieBuddy also needs javaPreload=true in mod.info, so a mod.info edit alone cannot make a jar preload
  manifest { attributes("ZB-Preload" to "true") }
}

tasks.named<Sync>("assembleMod") {
  from("src/main/lua") { into("42/media/lua") }
}
