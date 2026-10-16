package buildsrc.convention

// A feature module, bundled by a mod that sets pzGlue (dont39-all-in-one).
plugins {
  id("buildsrc.convention.pz-kotlin")
  id("com.google.devtools.ksp")
}

dependencies {
  // ships in ZabricKotlin's jar
  compileOnly(project(":zb-kt-utils"))
  // hook annotations and the runtime the generated glue calls
  compileOnly(project(":zabric"))
  ksp(project(":zb-kt-utils-processor"))
}

val pzGlue = extensions.create<PzGlue>("pzGlue")

fun Property<String>.required(name: String): Provider<String> {
  val why = "$path: pzGlue.$name is not set; the mod that bundles this feature sets it"
  return orElse(providers.provider<String> { throw GradleException(why) })
}

val featureName = project.name.split('-').joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }

ksp {
  arg("dont39.feature", featureName)
  arg("dont39.gluePackage", pzGlue.gluePackage.required("gluePackage"))
  arg("dont39.luaPrefix", pzGlue.luaPrefix.required("luaPrefix"))
  arg("zabric.modId", pzGlue.modId.required("modId"))
  // each feature is its own compilation inside the bundling mod, so each needs its own glue class
  arg("zabric.glueClass", "${featureName}Glue")
}

// the generated glue is ZombieBuddy code
tasks.named<CheckLoaderRefs>("checkLoaderRefs") {
  excludes.add(pzGlue.gluePackage.required("gluePackage").map { it.replace('.', '/') + "/*" })
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
