package com.dont39.pz.modsync

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class ManifestTest {
  @TempDir
  lateinit var folder: Path

  private val emptySha = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

  private fun build(): Manifest.Served {
    val item = writeItem(folder.resolve("2409333430"), SERVER_FILES)
    val empty = Files.createDirectories(folder.resolve("100"))
    return Manifest.build(
      listOf(
        Manifest.Source(2409333430, 1_760_000_000, item),
        Manifest.Source(100, 5, empty),
        Manifest.Source(2409333430, 1_760_000_000, item),
      ),
    )
  }

  @Test
  fun `the build lists every file under mods, hashed`() {
    val served = build()
    val item = served.manifest.items[0]
    assertEquals(Manifest.FORMAT, served.manifest.format)
    assertEquals(listOf("2409333430", "100"), served.manifest.items.map { it.id })
    assertEquals(1_760_000_000, item.timestamp)
    assertEquals(SERVER_FILES.keys.filter { it.startsWith("mods/") }.sorted(), item.files.map { it.path })
    val keep = item.files.first { it.path == "mods/Alpha/common/.keep" }
    assertEquals(ManifestFile("mods/Alpha/common/.keep", 0, emptySha), keep)
    assertEquals(emptyList<ManifestFile>(), served.manifest.items[1].files)
    assertEquals(5, served.fileCount)
    assertEquals(SERVER_FILES.filterKeys { it.startsWith("mods/") }.values.sumOf { it.length.toLong() }, served.bytes)
    // each hash maps to a file with that content
    item.files.forEach { assertEquals(it.sha256, sha256(checkNotNull(served.files[it.sha256]))) }
  }

  @Test
  fun `the JSON reads back as written`() {
    val served = build()
    assertEquals(served.manifest, Manifest.parse(served.json))
    val text = served.json.toString(Charsets.UTF_8)
    assertTrue(text.startsWith("""{"format":1,"items":[{"id":"2409333430","timestamp":1760000000,"files":[{"path":"""))
    assertArrayEquals(served.json, build().json)
  }

  @Test
  fun `an item is looked up by id and the timestamp the join sent`() {
    val manifest = build().manifest
    assertEquals("2409333430", manifest.item(2409333430, 1_760_000_000).id)
    assertTrue("does not offer" in assertThrows<SyncException> { manifest.item(7, 1) }.message!!)
    assertTrue("at version 1760000000" in assertThrows<SyncException> { manifest.item(2409333430, 1) }.message!!)
  }

  @Test
  fun `a missing install folder is an error of the server`() {
    assertThrows<IllegalStateException> { Manifest.build(listOf(Manifest.Source(1, 1, folder.resolve("gone")))) }
  }

  @Test
  fun `another format is refused`() {
    val e = assertThrows<SyncException> { Manifest.parse("""{"format":2,"items":[]}""".toByteArray()) }
    assertTrue("format 2" in e.message!!)
    assertThrows<SyncException> { Manifest.parse("""{"items":[]}""".toByteArray()) }
    assertThrows<SyncException> { Manifest.parse("""[1]""".toByteArray()) }
  }

  @Test
  fun `added fields are ignored`() {
    val text = """{"format":1,"extra":true,"items":[{"id":"1","timestamp":2,"files":[],"name":"x"}]}"""
    assertEquals(Manifest(1, listOf(ManifestItem("1", 2, emptyList()))), Manifest.parse(text.toByteArray()))
  }

  @TestFactory
  fun `a manifest that is not valid is refused`() = listOf(
    "not json",
    """{"format":1}""",
    """{"format":1,"items":[{"id":"1","timestamp":2}]}""",
    """{"format":1,"items":[{"id":"x","timestamp":2,"files":[]}]}""",
    """{"format":1,"items":[{"id":"01","timestamp":2,"files":[]}]}""",
    """{"format":1,"items":[{"id":"1","timestamp":2,"files":[]},{"id":"1","timestamp":3,"files":[]}]}""",
    file("../x"),
    file("mods/a/b", sha = "ABC"),
    file("mods/a/b", sha = emptySha.uppercase()),
    file("mods/a/b", size = -1),
    """{"format":1,"items":[{"id":"1","timestamp":2,"files":[${entry("mods/a/B")},${entry("mods/a/b")}]}]}""",
  ).map { text ->
    DynamicTest.dynamicTest(text) {
      assertThrows<SyncException> { Manifest.parse(text.toByteArray()) }
    }
  }

  @Test
  fun `a manifest larger than the limit is refused unread`() {
    assertThrows<SyncException> { Manifest.parse(ByteArray(Manifest.MAX_BYTES + 1)) }
  }

  private fun entry(path: String, size: Long = 0, sha: String = emptySha) =
    """{"path":"$path","size":$size,"sha256":"$sha"}"""

  private fun file(path: String, size: Long = 0, sha: String = emptySha) =
    """{"format":1,"items":[{"id":"1","timestamp":2,"files":[${entry(path, size, sha)}]}]}"""
}
