plugins {
  // The Kotlin DSL plugin provides a convenient way to develop convention plugins.
  // Convention plugins are located in `src/main/kotlin`, with the file extension `.gradle.kts`,
  // and are applied in the project's `build.gradle.kts` files as required.
  `kotlin-dsl`
}

kotlin {
  jvmToolchain(25)
}

// the .classtweaker parser, shared with the game-start widening
sourceSets.main {
  java.srcDir("../zabric/src/classtweaker/java")
}

dependencies {
  // Add a dependency on the Kotlin Gradle plugin, so that convention plugins can apply it.
  implementation(libs.kotlinGradlePlugin)
  // Lets pz-kotlin apply the kotlinx.serialization compiler plugin.
  implementation(libs.kotlinSerializationGradlePlugin)
  // Lets pz-feature apply KSP.
  implementation(libs.kspGradlePlugin)
  // Lets pz-feature widen the jars features compile against.
  implementation(libs.asm)
  // Lets pz-publish publish Zabric's artifacts to Maven Central.
  implementation(libs.mavenPublishGradlePlugin)
  implementation("io.github.zed-0xff.zb-gradle-plugin:io.github.zed-0xff.zb-gradle-plugin.gradle.plugin:1.0.3")
}
