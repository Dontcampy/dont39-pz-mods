plugins {
  // The Kotlin DSL plugin provides a convenient way to develop convention plugins.
  // Convention plugins are located in `src/main/kotlin`, with the file extension `.gradle.kts`,
  // and are applied in the project's `build.gradle.kts` files as required.
  `kotlin-dsl`
}

kotlin {
  jvmToolchain(25)
}

dependencies {
  // Add a dependency on the Kotlin Gradle plugin, so that convention plugins can apply it.
  implementation(libs.kotlinGradlePlugin)
  // Lets pz-kotlin apply the kotlinx.serialization compiler plugin.
  implementation(libs.kotlinSerializationGradlePlugin)
  // Lets pz-feature apply KSP, which generates the ZombieBuddy glue.
  implementation(libs.kspGradlePlugin)
  implementation("io.github.zed-0xff.zb-gradle-plugin:io.github.zed-0xff.zb-gradle-plugin.gradle.plugin:1.0.3")
}
