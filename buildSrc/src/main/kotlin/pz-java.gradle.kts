package buildsrc.convention

plugins {
  `java-library`
  id("io.github.zed-0xff.zb-gradle-plugin")
}

// Signs the mod jar (off for now).
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
}

val pzDir = providers.gradleProperty("pzDir")

// pz-feature swaps in a widened copy
val pzGameJar = objects.fileProperty().convention(layout.file(pzDir.map { file("$it/projectzomboid.jar") }))
extra["pzGameJar"] = pzGameJar

// Not on compileOnly: there zb-gradle-plugin 1.0.3 would add ZombieBuddy.jar as annotation processor (it has none) and a
// compileJava doFirst that holds the Project, which the configuration cache cannot store.
val zombieBuddy = configurations.dependencyScope("zombieBuddy")
configurations.compileClasspath { extendsFrom(zombieBuddy.get()) }

dependencies {
  zombieBuddy(files(pzDir.map { "$it/ZombieBuddy.jar" }))
  compileOnly(files(pzGameJar))
}
