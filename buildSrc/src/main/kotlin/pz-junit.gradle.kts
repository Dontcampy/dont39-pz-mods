package buildsrc.convention

import buildsrc.convention.mod.catalogLibrary

// JUnit 5 for a module's tests.
plugins {
  java
}

dependencies {
  testImplementation(platform(catalogLibrary("junitBom")))
  testImplementation(catalogLibrary("junitJupiter"))
  testRuntimeOnly(catalogLibrary("junitPlatformLauncher"))
}

tasks.test {
  useJUnitPlatform()
}
