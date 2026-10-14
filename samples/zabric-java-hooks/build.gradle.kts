import java.io.ByteArrayOutputStream
import java.util.zip.ZipFile
import javax.inject.Inject

// A Java mod that hooks the game through Zabric. Zabric comes from Maven (settings.gradle.kts); the game and
// ZombieBuddy jars come from your install: set pzDir in ~/.gradle/gradle.properties or pass -PpzDir=...
plugins {
  java
}

val zabricVersion = "0.4.0"
val modId = "ZabricJavaHooksSample"
val javaPkgName = "com.example.zabricsample"

// checked when compiling, so tasks like wrapper run without it
val pzDir = providers.gradleProperty("pzDir").orElse(
  provider { throw GradleException("Set pzDir to your Project Zomboid folder (the one with projectzomboid.jar).") }
)

java {
  toolchain {
    languageVersion = JavaLanguageVersion.of(25)
  }
}

dependencies {
  compileOnly("com.pzlauncher:zabric:$zabricVersion")
  annotationProcessor("com.pzlauncher:zabric-processor:$zabricVersion")
  compileOnly(files(pzDir.map { listOf("$it/projectzomboid.jar", "$it/ZombieBuddy.jar") }))
}

tasks.withType<JavaCompile>().configureEach {
  // the game runs on Java 25, but release 17 matches Zabric and ZombieBuddy
  options.release = 17
  options.encoding = "UTF-8"
  options.compilerArgs.addAll(listOf("-Azabric.modId=$modId", "-Azabric.javaPkgName=$javaPkgName"))
}

tasks.jar {
  archiveFileName = "$modId.jar"
}

// ---- checks: what the processor wrote, and what it rejects ----

val glueClass = "$javaPkgName.ZabricJavaHooksSampleGlue"
val hookClass = "$javaPkgName.hooks.GameTimeHooks"

// a line set, not text: the order follows the game jar's method order
val expectedListing = setOf(
  "mod $modId",
  "gate $javaPkgName.hooks.SampleGate",
  "patch $glueClass\$GameTime_update",
  "patch $glueClass\$GameTime_getHour",
  "patch $glueClass\$GameTime_getNightTint",
  "patch $glueClass\$GameTime_getMultiplierFromTimeDelta",
  "hook zombie.GameTime update (Z)V $glueClass\$GameTime_update",
  "hook zombie.GameTime getHour ()I $glueClass\$GameTime_getHour",
  "hook zombie.GameTime getNightTint ()F $glueClass\$GameTime_getNightTint",
  "hook zombie.GameTime getMultiplierFromTimeDelta (F)F $glueClass\$GameTime_getMultiplierFromTimeDelta",
)

val verifyGlue = tasks.register("verifyGlue") {
  group = "verification"
  description = "Checks the jar holds the generated glue and a listing with the expected patch and hook lines."
  val jar = tasks.jar.flatMap { it.archiveFile }
  inputs.file(jar)
  doLast {
    ZipFile(jar.get().asFile).use { zip ->
      val names = zip.entries().asSequence().map { it.name }.toSet()
      val glueBinary = glueClass.replace('.', '/')
      val patches = expectedListing.filter { it.startsWith("patch ") }
        .map { it.removePrefix("patch ").replace('.', '/') + ".class" }
      val missing = (listOf("$glueBinary.class") + patches).filter { it !in names }
      if (missing.isNotEmpty()) throw GradleException("glue classes missing from the jar: $missing")

      val listingName = "META-INF/zabric/hooks/$hookClass.txt"
      val listing = zip.getEntry(listingName) ?: throw GradleException("$listingName missing from the jar")
      val lines = zip.getInputStream(listing).bufferedReader().readLines().filter { it.isNotBlank() }
      if (lines.firstOrNull() != "zabric-hooks v1") throw GradleException("$listingName: bad header: ${lines.firstOrNull()}")
      val body = lines.drop(1).toSet()
      if (body != expectedListing) {
        throw GradleException(
          "$listingName differs; missing ${expectedListing - body}, unexpected ${body - expectedListing}"
        )
      }
      val others = names.filter { it.startsWith("META-INF/zabric/hooks/") && it != listingName && !it.endsWith("/") }
      if (others.isNotEmpty()) throw GradleException("unexpected listings: $others")
    }
  }
}

// Compiles one probe source set with the toolchain's javac and the published processor, then checks the outcome.
abstract class JavacProbe : DefaultTask() {
  @get:InputFiles
  abstract val sources: ConfigurableFileCollection

  @get:Classpath
  abstract val classpath: ConfigurableFileCollection

  @get:Classpath
  abstract val processorPath: ConfigurableFileCollection

  @get:Input
  abstract val processorOptions: ListProperty<String>

  @get:Input
  abstract val expectSuccess: Property<Boolean>

  /** Each must appear in javac's output, on a line saying error or warning as expected. */
  @get:Input
  abstract val expectedMessages: ListProperty<String>

  @get:Input
  abstract val javac: Property<String>

  @get:OutputDirectory
  abstract val outputDir: DirectoryProperty

  @get:Inject
  abstract val exec: ExecOperations

  @TaskAction
  fun compile() {
    val out = outputDir.get().asFile
    out.deleteRecursively()
    val classes = out.resolve("classes").apply { mkdirs() }
    val log = ByteArrayOutputStream()
    val result = exec.exec {
      executable = javac.get()
      // English diagnostics, whatever the system locale
      args("-J-Duser.language=en", "-J-Duser.country=US", "-encoding", "UTF-8", "--release", "17")
      args("-proc:full", "-processorpath", processorPath.asPath, "-classpath", classpath.asPath)
      args(processorOptions.get())
      args("-d", classes.absolutePath)
      args(sources.files.map { it.absolutePath })
      isIgnoreExitValue = true
      standardOutput = log
      errorOutput = log
    }
    val text = log.toString(Charsets.UTF_8)
    out.resolve("javac.log").writeText(text)
    val succeeded = result.exitValue == 0
    if (succeeded != expectSuccess.get()) {
      throw GradleException("javac ${if (succeeded) "succeeded" else "failed"}, expected otherwise:\n$text")
    }
    val kind = if (expectSuccess.get()) "warning:" else "error:"
    val missing = expectedMessages.get().filter { message ->
      text.lines().none { kind in it && message in it }
    }
    if (missing.isNotEmpty()) throw GradleException("no $kind line with $missing in:\n$text")
    if (succeeded && classes.resolve("META-INF/zabric").exists()) {
      throw GradleException("the processor wrote hook listings for a probe that has no hooks")
    }
  }
}

val probeJavac = javaToolchains.compilerFor { languageVersion = JavaLanguageVersion.of(25) }
  .map { it.executablePath.asFile.absolutePath }

fun registerProbe(name: String, succeeds: Boolean, vararg messages: String) =
  tasks.register<JavacProbe>("probe${name.split('-').joinToString("") { it.replaceFirstChar(Char::uppercase) }}") {
    group = "verification"
    description = "Compiles src/probes/$name; expects ${if (succeeds) "success with a warning" else "an error"}."
    sources.from(fileTree("src/probes/$name/java") { include("**/*.java") })
    classpath.from(configurations.compileClasspath)
    processorPath.from(configurations.annotationProcessor)
    processorOptions.addAll("-Azabric.modId=$modId", "-Azabric.javaPkgName=$javaPkgName")
    expectSuccess = succeeds
    expectedMessages.addAll(*messages)
    javac = probeJavac
    outputDir = layout.buildDirectory.dir("probes/$name")
  }

val probes = listOf(
  registerProbe(
    "unsafe-loader-patch", true,
    "$javaPkgName.LegacyPatch is a ZombieBuddy @Patch class marked @UnsafeLoaderPatch. Not recommended",
    "Zabric takes no responsibility when it breaks",
  ),
  registerProbe(
    "loader-patch", false,
    "$javaPkgName.LegacyPatch is a ZombieBuddy @Patch class: hook through Zabric's API instead",
    "Adding @UnsafeLoaderPatch lets it compile, but that is not recommended",
  ),
  registerProbe("unsupported-at", false, "InvokeHooks.beforeCall: @At(\"INVOKE\") is not supported"),
)

tasks.check {
  dependsOn(verifyGlue, probes)
}
