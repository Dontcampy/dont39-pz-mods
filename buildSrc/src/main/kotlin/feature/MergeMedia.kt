package buildsrc.convention.feature

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * A mod's `42/media` from folders laid out like it, e.g. the features' [PzArtifact.MOD_FILES]. The game reads one
 * translation file of each name per language and one `sandbox-options.txt` per mod, so those are merged; a key or
 * option in two folders fails, and so does any other file in two folders.
 */
@CacheableTask
abstract class MergeMedia : DefaultTask() {
  /** In order: merged files list their entries in this order. */
  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val folders: ConfigurableFileCollection

  @get:OutputDirectory
  abstract val merged: DirectoryProperty

  @TaskAction
  fun merge() {
    val byPath = sortedMapOf<String, MutableList<File>>()
    folders.files.filter { it.isDirectory }.forEach { root ->
      root.walkTopDown().filter { it.isFile }.forEach {
        byPath.getOrPut(it.relativeTo(root).invariantSeparatorsPath) { mutableListOf() } += it
      }
    }
    val out = merged.get().asFile
    out.deleteRecursively()
    byPath.forEach { (path, files) ->
      val target = out.resolve(path)
      target.parentFile.mkdirs()
      when {
        TRANSLATION.matches(path) -> target.writeText(mergeTranslations(files))
        path == SANDBOX_OPTIONS -> target.writeText(mergeSandboxOptions(files))
        files.size > 1 -> throw GradleException("$path is in more than one folder: ${files.joinToString()}")
        else -> files.single().copyTo(target)
      }
    }
  }

  private fun mergeTranslations(files: List<File>): String {
    val merged = linkedMapOf<String, Pair<String, File>>()
    files.forEach { file ->
      Translations.read(file).forEach { (key, value) ->
        merged.put(key, value to file)?.let { throw GradleException("$key is in both ${it.second} and $file") }
      }
    }
    return Translations.write(merged.mapValues { it.value.first })
  }

  private fun mergeSandboxOptions(files: List<File>): String {
    val owners = mutableMapOf<String, File>()
    val bodies = files.map { file ->
      val lines = file.readLines().dropWhile { it.isBlank() }
      if (lines.firstOrNull()?.trim() != SANDBOX_VERSION) {
        throw GradleException("$file does not start with '$SANDBOX_VERSION'")
      }
      lines.drop(1).forEach { line ->
        OPTION.find(line)?.let { m ->
          owners.put(m.groupValues[1], file)?.let {
            throw GradleException("option ${m.groupValues[1]} is in both $it and $file")
          }
        }
      }
      lines.drop(1).joinToString("\n").trim('\n', '\r', ' ')
    }
    return (listOf(SANDBOX_VERSION) + bodies.filter { it.isNotEmpty() }).joinToString("\n\n") + "\n"
  }

  private companion object {
    val TRANSLATION = Regex("lua/shared/Translate/[^/]+/[^/]+\\.json")
    const val SANDBOX_OPTIONS = "sandbox-options.txt"
    const val SANDBOX_VERSION = "VERSION = 1,"
    val OPTION = Regex("^\\s*option\\s+(\\S+)")
  }
}

/** The game's translation files: one JSON object of strings, which a key twice in one file fails. */
internal object Translations {
  fun read(file: File): Map<String, String> {
    val text = file.readText()
    var i = 0
    fun fail(what: String): Nothing = throw GradleException("$file: $what at offset $i")
    fun skip() {
      while (i < text.length && text[i].isWhitespace()) i++
    }
    fun expect(c: Char) {
      skip()
      if (i >= text.length || text[i] != c) fail("expected '$c'")
      i++
    }
    fun string(): String {
      expect('"')
      val sb = StringBuilder()
      while (true) {
        if (i >= text.length) fail("unterminated string")
        when (val c = text[i++]) {
          '"' -> return sb.toString()
          '\\' -> {
            if (i >= text.length) fail("unterminated string")
            when (val e = text[i++]) {
              '"', '\\', '/' -> sb.append(e)
              'b' -> sb.append('\b')
              'f' -> sb.append('\u000C')
              'n' -> sb.append('\n')
              'r' -> sb.append('\r')
              't' -> sb.append('\t')
              'u' -> {
                if (i + 4 > text.length) fail("short \\u escape")
                sb.append(text.substring(i, i + 4).toIntOrNull(16)?.toChar() ?: fail("bad \\u escape"))
                i += 4
              }
              else -> fail("bad escape \\$e")
            }
          }
          else -> if (c < ' ') fail("control character in a string") else sb.append(c)
        }
      }
    }

    if (text.startsWith('﻿')) i++
    val entries = linkedMapOf<String, String>()
    expect('{')
    skip()
    if (i < text.length && text[i] == '}') {
      i++
    } else {
      while (true) {
        val key = string()
        expect(':')
        skip()
        if (i >= text.length || text[i] != '"') fail("the value of $key is not a string")
        if (entries.put(key, string()) != null) fail("$key twice")
        skip()
        if (i < text.length && text[i] == ',') {
          i++
          continue
        }
        expect('}')
        break
      }
    }
    skip()
    if (i != text.length) fail("text after the object")
    return entries
  }

  /** Indented by four spaces, non-ASCII as is, like the source files. */
  fun write(entries: Map<String, String>): String {
    if (entries.isEmpty()) return "{}\n"
    return entries.entries.joinToString(",\n", "{\n", "\n}\n") { (k, v) -> "    ${quote(k)}: ${quote(v)}" }
  }

  private fun quote(s: String) = buildString {
    append('"')
    s.forEach { c ->
      when (c) {
        '"' -> append("\\\"")
        '\\' -> append("\\\\")
        '\n' -> append("\\n")
        '\r' -> append("\\r")
        '\t' -> append("\\t")
        '\b' -> append("\\b")
        '\u000C' -> append("\\f")
        else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
      }
    }
    append('"')
  }
}
