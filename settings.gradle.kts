dependencyResolutionManagement {
  @Suppress("UnstableApiUsage")
  repositories {
    mavenCentral()
  }
}

plugins {
  // downloads the JDK of pz-jvm's toolchain if it is not installed
  id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

include(":mod-options-sidebar")
include(":ime-keyboard-fix")
include(":missing-anim-folders")
include(":anim-extends-case")
include(":tunnel")
include(":network-status")
include(":zb-kt")
include(":zb-kt-utils")
include(":zb-kt-utils-processor")
include(":dont39-all-in-one")
include(":zabric")
include(":zabric-processor")
include(":zb-skip-online-checks")

rootProject.name = "dont39-pz-mods"
