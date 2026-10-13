package buildsrc.convention

plugins {
  `java-library`
  id("io.github.zed-0xff.zb-gradle-plugin")
}

// Wires ZombieBuddy.jar in as annotation processor for @Patch classes; only the shipped mod jar is signed.
zbSigning {
  enabled = false
}

java {
  toolchain {
    languageVersion = JavaLanguageVersion.of(25)
  }
}

tasks.withType<JavaCompile>().configureEach {
  options.release = 17
  options.encoding = "UTF-8"
  notCompatibleWithConfigurationCache("zb-gradle-plugin 1.0.3 adds a doFirst that references the Project")
}

val pzDir = providers.gradleProperty("pzDir")

// pz-feature swaps in a widened copy
val pzGameJar = objects.fileProperty().convention(layout.file(pzDir.map { file("$it/projectzomboid.jar") }))
extra["pzGameJar"] = pzGameJar

dependencies {
  compileOnly(files(pzDir.map { "$it/ZombieBuddy.jar" }))
  compileOnly(files(pzGameJar))
}
