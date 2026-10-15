// Standalone build: not part of the dont39-pz-mods build, and resolves Zabric only from Maven repositories.
dependencyResolutionManagement {
  @Suppress("UnstableApiUsage")
  repositories {
    // publishToMavenLocal output before a release; Maven Central otherwise
    mavenLocal()
    mavenCentral()
  }
}

plugins {
  id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "zabric-java-hooks"
