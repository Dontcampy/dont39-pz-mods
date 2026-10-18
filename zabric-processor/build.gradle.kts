import buildsrc.convention.ZOMBIEBUDDY_JAR
import buildsrc.convention.pzDir

// Zabric's annotation processor: authors use it as annotationProcessor. Plain Java whose only dependency is FreeMarker,
// so it builds and publishes on its own; it names Zabric's and ZombieBuddy's types as strings.
plugins {
  id("buildsrc.convention.pz-jvm")
  `java-library`
  id("buildsrc.convention.pz-junit")
  id("buildsrc.convention.pz-publish")
}

version = libs.versions.zabric.get()

mavenPublishing {
  pom {
    name = "Zabric Processor"
    description = "Annotation processor for Zabric hooks: checks hook classes against the compile classpath and " +
      "generates their loader glue and listings."
  }
}

// the hook listing format, shared with Zabric's reader
sourceSets.main {
  java.srcDir(layout.settingsDirectory.dir("zabric/src/listingformat/java"))
}

dependencies {
  implementation(libs.freemarker)
  // what the in-process compilations compile against: Zabric's hook API and ZombieBuddy's @Patch
  testRuntimeOnly(project(":zabric"))
  testRuntimeOnly(files(pzDir().map { it.file(ZOMBIEBUDDY_JAR) }))
}

val weaveSources = layout.settingsDirectory.dir("zabric/src/test")

tasks.test {
  // the golden test compiles the weave test's hook classes and compares with its reference glue and listings
  inputs.dir(weaveSources).withPropertyName("weaveSources")
  systemProperty("zabric.weaveSources", weaveSources.asFile.absolutePath)
}
