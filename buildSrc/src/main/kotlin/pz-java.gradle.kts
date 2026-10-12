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

dependencies {
  compileOnly(files(pzDir.map { "$it/ZombieBuddy.jar" }))
  compileOnly(files(pzDir.map { "$it/projectzomboid.jar" }))
}
