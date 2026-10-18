package com.dont39.pz.zbktutils.processor

import com.tschuchort.compiletesting.SourceFile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** The glue and listings of a representative set of hook objects, against the files under `golden/expected`. */
class GoldenTest {
  private val golden = File(javaClass.getResource("/golden")!!.toURI())

  @Test
  fun hooksGenerateTheExpectedGlue(@TempDir dir: File) {
    val sources = golden.resolve("src").listFiles()!!.sortedBy { it.name }.map {
      val text = read(it)
      if (it.extension == "java") SourceFile.java(it.name, text) else SourceFile.kotlin(it.name, text)
    }
    val result = ksp(dir, sources)
    result.assertCompiles()
    assertEquals(listOf<String>(), result.warnings)
    val expectedDir = golden.resolve("expected")
    val expected = expectedDir.walk().filter { it.isFile }
      .associate { it.relativeTo(expectedDir).invariantSeparatorsPath to read(it) }
    assertEquals(expected.keys, result.generated.keys)
    expected.forEach { (path, text) -> assertEquals(text, result.generated[path], path) }
  }

  // a Windows checkout with core.autocrlf has CRLF; the generator writes LF
  private fun read(file: File) = file.readText().replace("\r\n", "\n")
}
