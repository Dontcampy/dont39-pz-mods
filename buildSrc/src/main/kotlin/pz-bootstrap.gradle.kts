package buildsrc.convention

// A module whose Java classes run before ZabricKotlin has installed Kotlin, or without it.
plugins {
  java
}

val checkBootstrapClasses = tasks.register<CheckBootstrapClasses>("checkBootstrapClasses") {
  description = "Fails if a Java class of this module references a kotlin/ type."
  classes.from(tasks.compileJava.flatMap { it.destinationDirectory })
}

tasks.check { dependsOn(checkBootstrapClasses) }
tasks.jar { dependsOn(checkBootstrapClasses) }
