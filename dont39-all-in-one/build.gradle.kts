plugins {
    id("buildsrc.convention.pz-java")
}

version = "0.1.0"

val modId = "Dont39AllInOne"
val features = listOf(":viewpoint-blood-fx-fix")

features.forEach { evaluationDependsOn(it) }

dependencies {
    features.forEach { implementation(project(it)) }
}

tasks.jar {
    archiveFileName = "$modId.jar"
    features.forEach { from(project(it).the<SourceSetContainer>()["main"].output) }
    duplicatesStrategy = DuplicatesStrategy.FAIL
    manifest {
        attributes("Implementation-Title" to modId, "Implementation-Version" to project.version)
    }
}

val assembleMod = tasks.register<Sync>("assembleMod") {
    group = "mod"
    description = "Assembles the $modId mod folder."
    val version = project.version.toString()
    into(layout.buildDirectory.dir("mod/$modId"))
    from("mod/common") { into("common") }
    from("mod/42") { into("42") }
    from(tasks.jar) { into("42/media/java/client") }
    // expand() would treat the backslashes in require=\ZombieBuddy as escapes
    filesMatching("**/mod.info") { filter { it.replace("@VERSION@", version) } }
}

val pzUserDir = providers.gradleProperty("pzUserDir").orElse(providers.systemProperty("user.home").map { "$it/Zomboid" })
val workshopDir = pzUserDir.map { "$it/Workshop/$modId" }

tasks.register<Copy>("deployWorkshop") {
    group = "mod"
    description = "Copies $modId into ~/Zomboid/Workshop for local testing and uploading."
    val staged = file(workshopDir.get())
    val modFolder = staged.resolve("Contents/mods/$modId")
    val repoWorkshopTxt = file("mod/workshop/workshop.txt")
    doFirst {
        // the uploader writes the Workshop id into the staged copy
        val stagedTxt = staged.resolve("workshop.txt")
        val stagedId = if (stagedTxt.exists()) stagedTxt.readLines().firstOrNull { it.startsWith("id=") } else null
        if (stagedId != null && repoWorkshopTxt.readLines().none { it == stagedId }) {
            throw GradleException("$stagedTxt has '$stagedId' but $repoWorkshopTxt does not; add it there first.")
        }
        modFolder.deleteRecursively()
    }
    into(staged)
    from("mod/workshop")
    from(assembleMod) { into("Contents/mods/$modId") }
}
