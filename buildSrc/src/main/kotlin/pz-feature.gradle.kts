package buildsrc.convention

// An All-in-One feature module.
plugins {
  id("buildsrc.convention.pz-kotlin")
  id("com.google.devtools.ksp")
}

dependencies {
  implementation(project(":zombiebuddy-kotlin-utils"))
  ksp(project(":zombiebuddy-kotlin-utils-processor"))
}

ksp {
  arg("dont39.feature", project.name.split('-').joinToString("") { it.replaceFirstChar(Char::uppercaseChar) })
  // All-in-One's javaPkgName; ZombieBuddy only looks there
  arg("dont39.gluePackage", "com.dont39.pz.allinone")
  // Lua globals are shared by all mods
  arg("dont39.luaPrefix", "Dont39AllInOne_")
}

val pzDir = providers.gradleProperty("pzDir")
val gameJarName = "projectzomboid.jar"
val gameJar = pzDir.map { file("$it/$gameJarName") }
val pzTargets = extensions.create<PzTargets>(
  "pzTargets",
  file(pzDir.get()).resolve("../../workshop/content/108600").normalize(),
)

// Members the feature reaches directly although they are not public; the same list is applied at game start.
val classTweaker = layout.projectDirectory.file("src/main/resources/META-INF/dont39/${project.name}.classtweaker")
if (classTweaker.asFile.exists()) {
  val widen = tasks.register<WidenJars>("widenCompileJars") {
    description = "Widens the jars ${project.name} compiles against as its .classtweaker lists."
    tweakers.from(classTweaker)
    jars.from(gameJar, pzTargets.jars)
    widened = layout.buildDirectory.dir("classtweaker/jars")
    expanded = layout.buildDirectory.file("classtweaker/${project.name}.classtweaker")
  }
  (extra["pzGameJar"] as RegularFileProperty).set(widen.flatMap { it.widened.file(gameJarName) })
  val widenedTargets = files(widen.flatMap { it.widened }).asFileTree.matching { exclude(gameJarName) }
  dependencies { compileOnly(widenedTargets) }
} else {
  dependencies { compileOnly(pzTargets.jars) }
}
