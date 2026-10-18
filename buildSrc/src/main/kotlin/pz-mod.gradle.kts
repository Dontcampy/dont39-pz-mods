package buildsrc.convention

// A shipped mod: its id and javaPkgName come from mod/42/mod.info (pzMod). Registers assembleMod (the mod folder with
// the jar; the module adds any other files), published as the mod-folder variant, and deployWorkshop.
plugins {
  java
}

val modInfo = readModInfo(layout.projectDirectory.file("mod/42/mod.info"))
val modId = modInfo.id
extensions.create<PzMod>("pzMod", modId, modInfo.javaPkgName)

tasks.jar {
  archiveFileName = "$modId.jar"
  manifest {
    attributes("Implementation-Title" to modId, "Implementation-Version" to project.version)
  }
}

val modFolder = layout.buildDirectory.dir("mod/$modId")

val assembleMod = tasks.register<Sync>("assembleMod") {
  group = "mod"
  description = "Assembles the $modId mod folder."
  val version = project.version.toString()
  into(modFolder)
  from("mod/42") { into("42") }
  // no client/ or server/ in the path: loads on both (ZombieBuddy ModdingGuide, "Client/server JAR paths")
  from(tasks.jar) { into("42/media/java") }
  duplicatesStrategy = DuplicatesStrategy.FAIL
  // expand() would treat the backslashes in require=\ZombieBuddy as escapes
  filesMatching("**/mod.info") { filter { it.replace("@VERSION@", version) } }
  doLast { destinationDir.resolve("common").mkdirs() }
}

pzElements("pzModFolderElements", PzArtifact.MOD_FOLDER).configure {
  outgoing.artifact(modFolder) { builtBy(assembleMod) }
}

if (file("mod/workshop").isDirectory) {
  tasks.register<Copy>("deployWorkshop") {
    group = "mod"
    description = "Copies $modId into ~/Zomboid/Workshop for local testing and uploading."
    val staged = pzUserDir().map { it.dir("Workshop/$modId") }
    val repoWorkshopTxt = file("mod/workshop/workshop.txt")
    doFirst {
      val stagedDir = staged.get().asFile
      // the uploader writes the Workshop id into the staged copy
      val stagedTxt = stagedDir.resolve("workshop.txt")
      val stagedId = if (stagedTxt.exists()) stagedTxt.readLines().firstOrNull { it.startsWith("id=") } else null
      if (stagedId != null && repoWorkshopTxt.readLines().none { it == stagedId }) {
        throw GradleException("$stagedTxt has '$stagedId' but $repoWorkshopTxt does not; add it there first.")
      }
      // also drops a mod the item no longer ships
      stagedDir.resolve("Contents/mods").deleteRecursively()
    }
    into(staged)
    from("mod/workshop")
    from(assembleMod) { into("Contents/mods/$modId") }
  }
}
