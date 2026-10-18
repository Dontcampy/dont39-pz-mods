package buildsrc.convention

// Toolchain and bytecode level, without zb-gradle-plugin: the processors apply this alone.
plugins {
  java
}

java {
  toolchain {
    languageVersion = JavaLanguageVersion.of(PZ_TOOLCHAIN)
  }
}

tasks.withType<JavaCompile>().configureEach {
  options.release = PZ_RELEASE
  options.encoding = "UTF-8"
}
