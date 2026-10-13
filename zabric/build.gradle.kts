import buildsrc.convention.CheckBootstrapClasses

// Java (and Lua) only: ZombieBuddy runs PreMain before ZombieBuddyKotlin has installed the Kotlin runtime.
plugins {
  id("buildsrc.convention.pz-java")
  id("buildsrc.convention.pz-mod")
}

version = "0.2.0"

// the .classtweaker parser, shared with buildSrc
sourceSets.main {
  java.srcDir("src/classtweaker/java")
}

val checkBootstrapClasses = tasks.register<CheckBootstrapClasses>("checkBootstrapClasses") {
  description = "Fails if a class of this module references a kotlin/ type."
  classes.from(tasks.compileJava.flatMap { it.destinationDirectory })
}

tasks.check { dependsOn(checkBootstrapClasses) }

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
