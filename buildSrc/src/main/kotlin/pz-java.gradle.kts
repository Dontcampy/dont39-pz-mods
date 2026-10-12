package buildsrc.convention

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
    compileOnly(files(pzDir.map { "$it/ZombieBuddy.jar" }))
    compileOnly(files(pzDir.map { "$it/projectzomboid.jar" }))
}
