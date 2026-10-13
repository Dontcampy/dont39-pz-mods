package buildsrc.convention

// A shipped mod: its id comes from mod/42/mod.info. Registers assembleMod (the mod folder; the module adds its jar and
// any other files) and deployWorkshop. Exposes modId and pzUserDir as extra properties for the module's build script.
plugins {
  java
}

val modInfo = providers.fileContents(layout.projectDirectory.file("mod/42/mod.info")).asText
val modId = modInfo.get().lines().firstOrNull { it.startsWith("id=") }?.substringAfter("id=")?.trim()
  ?: throw GradleException("${project.path}: mod/42/mod.info has no id= line")

val pzUserDir =
  providers.gradleProperty("pzUserDir").orElse(providers.systemProperty("user.home").map { "$it/Zomboid" })
val workshopDir = pzUserDir.map { "$it/Workshop/$modId" }

extra["modId"] = modId
extra["pzUserDir"] = pzUserDir

tasks.jar {
  archiveFileName = "$modId.jar"
  manifest {
    attributes("Implementation-Title" to modId, "Implementation-Version" to project.version)
  }
}

val assembleMod = tasks.register<Sync>("assembleMod") {
  group = "mod"
  description = "Assembles the $modId mod folder."
  val version = project.version.toString()
  into(layout.buildDirectory.dir("mod/$modId"))
  from("mod/42") { into("42") }
  duplicatesStrategy = DuplicatesStrategy.FAIL
  // expand() would treat the backslashes in require=\ZombieBuddy as escapes
  filesMatching("**/mod.info") { filter { it.replace("@VERSION@", version) } }
  doLast { destinationDir.resolve("common").mkdirs() }
}

if (file("mod/workshop").isDirectory) {
  tasks.register<Copy>("deployWorkshop") {
    group = "mod"
    description = "Copies $modId into ~/Zomboid/Workshop for local testing and uploading."
    val staged = file(workshopDir.get())
    val modsFolder = staged.resolve("Contents/mods")
    val repoWorkshopTxt = file("mod/workshop/workshop.txt")
    doFirst {
      // the uploader writes the Workshop id into the staged copy
      val stagedTxt = staged.resolve("workshop.txt")
      val stagedId = if (stagedTxt.exists()) stagedTxt.readLines().firstOrNull { it.startsWith("id=") } else null
      if (stagedId != null && repoWorkshopTxt.readLines().none { it == stagedId }) {
        throw GradleException("$stagedTxt has '$stagedId' but $repoWorkshopTxt does not; add it there first.")
      }
      // also drops a mod the item no longer ships
      modsFolder.deleteRecursively()
    }
    into(staged)
    from("mod/workshop")
    from(assembleMod) { into("Contents/mods/$modId") }
  }
}
