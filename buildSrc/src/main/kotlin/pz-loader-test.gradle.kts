package buildsrc.convention

// Tests that run code against ZombieBuddy's own classes, with an Instrumentation as ZombieBuddy has at game start.
plugins {
  id("buildsrc.convention.pz-java")
  id("buildsrc.convention.pz-junit")
}

val zombieBuddy = configurations.named("zombieBuddy")
configurations.testCompileClasspath { extendsFrom(zombieBuddy.get()) }
configurations.testRuntimeClasspath { extendsFrom(zombieBuddy.get()) }

tasks.test {
  // ByteBuddy's agent attaches to the test JVM for the Instrumentation
  jvmArgs("-Djdk.attach.allowAttachSelf=true", "-XX:+EnableDynamicAgentLoading")
}
