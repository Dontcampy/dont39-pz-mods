package buildsrc.convention

import buildsrc.convention.mod.PzMod

// A shipped mod with switches in an ini file and on its mod options page, from mod-settings/: Switches.java (and the
// tests' SwitchesPage.java) compiled into <javaPkgName>.settings, Switches.lua shipped as <modId>_Switches.lua. The
// mods share one class loader and one Lua require namespace, so each gets the code under its own names.
plugins {
  id("buildsrc.convention.pz-mod")
}

val pzMod = the<PzMod>()
val modId = pzMod.modId
val pkg = "${pzMod.javaPkgName}.settings"
val shared = layout.settingsDirectory.dir("mod-settings")

fun generate(name: String, file: String, set: String) = tasks.register<Sync>(name) {
  description = "Copies mod-settings/$file into $pkg."
  into(layout.buildDirectory.dir("generated/sources/switches/$set/java"))
  from(shared.file(file)) { into(pkg.replace('.', '/')) }
  val declared = "package com.dont39.pz.modsettings;"
  val replaced = "package $pkg;"
  filter { line ->
    if (line.startsWith("package ")) {
      if (line != declared) throw GradleException("mod-settings/$file: '$line' is not '$declared'")
      replaced
    } else {
      line
    }
  }
}

sourceSets.main { java.srcDir(generate("generateSwitches", "Switches.java", "main")) }
sourceSets.test { java.srcDir(generate("generateSwitchesPage", "SwitchesPage.java", "test")) }

tasks.named<Sync>("assembleMod") {
  val name = "${modId}_Switches.lua"
  from(shared.file("Switches.lua")) {
    into("42/media/lua/client")
    rename { name }
  }
}
