// Java (and Lua) only: ZombieBuddy runs PreMain before ZombieBuddyKotlin has installed the Kotlin runtime. Shipped in
// Dont39 All-in-One's Workshop item; dont39-all-in-one adds the merged .classtweaker list and deploys it.
plugins {
  id("buildsrc.convention.pz-java")
  id("buildsrc.convention.pz-mod")
}

version = "0.1.0"

// the .classtweaker parser, shared with buildSrc
sourceSets.main {
  java.srcDir("src/classtweaker/java")
}

tasks.jar {
  // ZombieBuddy also needs javaPreload=true in mod.info, so a mod.info edit alone cannot make a jar preload
  manifest { attributes("ZB-Preload" to "true") }
}

tasks.named<Sync>("assembleMod") {
  from(tasks.jar) { into("42/media/java/client") }
  from("src/main/lua") { into("42/media/lua") }
}
