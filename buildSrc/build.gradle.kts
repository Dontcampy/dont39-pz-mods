plugins {
  `kotlin-dsl`
}

kotlin {
  jvmToolchain(25)
}

// the .classtweaker parser, shared with the game-start widening; the hook listing format, shared with Zabric
sourceSets.main {
  java.srcDir("../zabric/src/classtweaker/java")
  java.srcDir("../zabric/src/listingformat/java")
}

dependencies {
  // Lets pz-kotlin apply Kotlin.
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
