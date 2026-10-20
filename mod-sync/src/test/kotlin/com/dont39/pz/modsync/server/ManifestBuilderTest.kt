package com.dont39.pz.modsync.server

import com.dont39.pz.modsync.SERVER_FILES
import com.dont39.pz.modsync.protocol.Manifest
import com.dont39.pz.modsync.protocol.ManifestFile
import com.dont39.pz.modsync.protocol.SyncException
import com.dont39.pz.modsync.protocol.sha256
import com.dont39.pz.modsync.writeItem
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class ManifestBuilderTest {
  @TempDir
  lateinit var folder: Path

  private val emptySha = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

  private fun build(): ManifestBuilder.Served {
    val item = writeItem(folder.resolve("2409333430"), SERVER_FILES)
    val empty = Files.createDirectories(folder.resolve("100"))
    return ManifestBuilder.build(
      listOf(
        ManifestBuilder.Source(2409333430, 1_760_000_000, item),
        ManifestBuilder.Source(100, 5, empty),
        ManifestBuilder.Source(2409333430, 1_760_000_000, item),
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
    assertThrows<IllegalStateException> {
      ManifestBuilder.build(listOf(ManifestBuilder.Source(1, 1, folder.resolve("gone"))))
    }
  }
}
