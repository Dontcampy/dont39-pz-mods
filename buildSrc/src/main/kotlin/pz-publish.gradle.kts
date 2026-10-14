package buildsrc.convention

import com.vanniktech.maven.publish.JavaLibrary
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.SourcesJar
import org.gradle.plugins.signing.SigningExtension

// A Maven Central artifact under com.pzlauncher (Zabric's). The module sets its version, POM name and description.
// Credentials and signing keys come from ~/.gradle/gradle.properties or ORG_GRADLE_PROJECT_* variables only.
plugins {
  `java-library`
  id("com.vanniktech.maven.publish")
}

group = "com.pzlauncher"

val repoUrl = "https://github.com/Dontcampy/dont39-pz-mods"

// Signs only when keys are set, so publishToMavenLocal works without them: once signing is on, the plugin requires it.
val signed = listOf("signingInMemoryKey", "signing.keyId", "signing.gnupg.keyName")
  .any { providers.gradleProperty(it).isPresent }
if (!signed && gradle.startParameter.taskNames.any { "MavenCentral" in it }) {
  throw GradleException(
    "${project.path}: Maven Central needs signed artifacts; set signingInMemoryKey, signing.keyId or " +
      "signing.gnupg.keyName in ~/.gradle/gradle.properties."
  )
}

tasks.javadoc {
  (options as StandardJavadocDocletOptions).apply {
    encoding = "UTF-8"
    locale = "en"
    // the docs say what each member is for; tags for every parameter would mostly restate the signature
    addBooleanOption("Xdoclint:all,-missing", true)
  }
}

mavenPublishing {
  configure(JavaLibrary(javadocJar = JavadocJar.Javadoc(), sourcesJar = SourcesJar.Sources()))
  publishToMavenCentral()
  if (signed) {
    signAllPublications()
    if (providers.gradleProperty("signing.gnupg.keyName").isPresent) {
      the<SigningExtension>().useGpgCmd()
    }
  }
  pom {
    inceptionYear = "2026"
    url = repoUrl
    licenses {
      license {
        name = "MIT License"
        url = "https://opensource.org/license/mit"
        distribution = "repo"
      }
    }
    developers {
      developer {
        id = "Dont39"
        name = "Dont39"
        url = "https://github.com/Dontcampy"
      }
    }
    scm {
      url = repoUrl
      connection = "scm:git:https://github.com/Dontcampy/dont39-pz-mods.git"
      developerConnection = "scm:git:ssh://git@github.com/Dontcampy/dont39-pz-mods.git"
    }
  }
}
