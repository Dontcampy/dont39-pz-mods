import buildsrc.convention.check.CheckJdkGlue
import buildsrc.convention.check.CheckLoaderRefs
import buildsrc.convention.mod.modInfoOf
import buildsrc.convention.mod.pzDir
import buildsrc.convention.mod.pzUserDir
import buildsrc.convention.smoke.GameSmokeTest

// Java: its hooks are written for zabric-processor, the javac glue front end; the KSP one serves only
// All-in-One features.
plugins {
  id("buildsrc.convention.pz-java")
  id("buildsrc.convention.pz-mod")
  id("buildsrc.convention.pz-bootstrap")
  id("buildsrc.convention.pz-widening")
  id("buildsrc.convention.pz-junit")
}

version = "0.1.0"

val modId = pzMod.modId
val pkg = pzMod.javaPkgName
val pkgDir = pkg.replace('.', '/')

dependencies {
  compileOnly(project(":zabric"))
  annotationProcessor(project(":zabric-processor"))
}

tasks.compileJava {
  options.compilerArgs.addAll(listOf("-Azabric.modId=$modId", "-Azabric.javaPkgName=$pkg"))
}

// the generated glue is ZombieBuddy code
tasks.named<CheckLoaderRefs>("checkLoaderRefs") {
  excludes.add("$pkgDir/*.class")
}

val checkJdkGlue = tasks.register<CheckJdkGlue>("checkJdkGlue") {
  description = "Fails if the generated glue names a type outside the JDK."
  classes = tasks.compileJava.flatMap { it.destinationDirectory }
  gluePackage = pkgDir
}
tasks.check { dependsOn(checkJdkGlue) }

tasks.jar {
  dependsOn(checkJdkGlue)
  manifest {
    attributes("Zabric-Main" to "$pkg.entrypoint.MainEntry")
  }
  // build data only (the source list)
  exclude("META-INF/dont39/**")
  // where Zabric reads it at game start
  if (pzWidening.listed) {
    val listName = "$modId.classtweaker"
    from(pzWidening.expanded) {
      into("META-INF/zabric")
      rename { listName }
    }
  }
}

// the notice's text
tasks.named<Sync>("assembleMod") {
  from("src/main/lua") { into("42/media/lua") }
}

tasks.register<GameSmokeTest>("smokeTest") {
  description = "Launches Project Zomboid until ZombieBuddy has loaded $modId, then checks the log."
  dependsOn(tasks.named("deployWorkshop"), ":zabric:deployWorkshop")
  gameDir = pzDir()
  console = pzUserDir().map { it.file("console.txt") }
  modId = pzMod.modId
  version = project.version.toString()
  zabricId = modInfoOf("zabric").id
  widener = pzWidening.listed
}
