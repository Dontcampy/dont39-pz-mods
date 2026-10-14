// Zabric's annotation processor: authors use it as annotationProcessor. Plain Java with no dependencies, so it builds
// and publishes on its own; it names Zabric's and ZombieBuddy's types as strings.
plugins {
  `java-library`
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

dependencies {
  testImplementation(platform(libs.junitBom))
  testImplementation(libs.junitJupiter)
  testRuntimeOnly(libs.junitPlatformLauncher)
  // what the in-process compilations compile against: Zabric's hook API and ZombieBuddy's @Patch
  testRuntimeOnly(project(":zabric"))
  testRuntimeOnly(files(pzDir.map { "$it/ZombieBuddy.jar" }))
}

val weaveSources = rootProject.layout.projectDirectory.dir("zabric/src/test")

tasks.test {
  useJUnitPlatform()
  // the golden test compiles the weave test's hook classes and compares with its reference glue and listings
  inputs.dir(weaveSources).withPropertyName("weaveSources")
  systemProperty("zabric.weaveSources", weaveSources.asFile.absolutePath)
}
