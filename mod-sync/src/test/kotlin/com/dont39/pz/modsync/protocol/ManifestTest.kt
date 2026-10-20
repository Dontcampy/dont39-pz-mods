package com.dont39.pz.modsync.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.assertThrows

class ManifestTest {
  private val emptySha = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

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
