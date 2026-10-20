package com.dont39.pz.zbktutils.processor

import com.tschuchort.compiletesting.DiagnosticSeverity
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.configureKsp
import com.tschuchort.compiletesting.kspSourcesDir
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import java.io.File

/** The options pz-feature.gradle.kts passes. */
internal val OPTIONS = mapOf(
  "dont39.feature" to "Test",
  "dont39.gluePackage" to "glue",
  "dont39.luaPrefix" to "test_",
  "zabric.modId" to "test-mod",
  "zabric.glueClass" to "TestGlue",
)

// the processor's messages come with their location when they have one
private val LOCATION = Regex("""^.+?:\d+: """)
private const val KSP_FAILED = "Error occurred in KSP, check log for detail"

/** A KSP run of [GlueProcessor]; on success the sources and generated files are also compiled. */
internal class KspResult(
  val success: Boolean,
  val errors: List<String>,
  val warnings: List<String>,
  private val out: File,
) {
  /** Generated files by path under KSP's output (`java/...`, `kotlin/...`, `resources/...`). */
  val generated: Map<String, String>
    get() = out.walk().filter { it.isFile }
      .associate { it.relativeTo(out).invariantSeparatorsPath to it.readText() }

  fun assertCompiles() {
    assertEquals(listOf<String>(), errors)
    assertTrue(success)
  }

  /** Fails the run, with [message] among the errors. */
  fun assertError(message: String) {
    assertTrue(!success && message in errors, "no error \"$message\" in $errors")
  }
}

@OptIn(ExperimentalCompilerApi::class)
internal fun ksp(
  dir: File,
  sources: List<SourceFile>,
  options: Map<String, String> = OPTIONS,
  classpath: List<File> = listOf(),
): KspResult {
  val compilation = KotlinCompilation().apply {
    workingDir = dir
    inheritClassPath = true
    classpaths += classpath
    this.sources = sources
    configureKsp {
      symbolProcessorProviders += GlueProcessorProvider()
      processorOptions += options
    }
  }
  val result = compilation.compile()
  fun messages(severity: DiagnosticSeverity) = result.diagnosticMessages
    .filter { it.severity == severity && it.message != KSP_FAILED }
    .map { it.message.replaceFirst(LOCATION, "") }
  return KspResult(
    result.exitCode == KotlinCompilation.ExitCode.OK,
    messages(DiagnosticSeverity.ERROR),
    messages(DiagnosticSeverity.WARNING),
    compilation.kspSourcesDir,
  )
}

internal fun ksp(dir: File, vararg sources: SourceFile) = ksp(dir, sources.toList())

internal fun kotlin(name: String, text: String) = SourceFile.kotlin(name, text.trimIndent())

internal fun java(name: String, text: String) = SourceFile.java(name, text.trimIndent())
