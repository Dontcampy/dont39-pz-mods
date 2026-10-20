package com.dont39.pz.common

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class WriteAtomicallyTest {
  @TempDir
  lateinit var folder: Path

  @Test
  fun `the file is written through its tmp sibling, folders included`() {
    val path = folder.resolve("sub/store.txt")
    writeAtomically(path) { temp ->
      assertEquals(folder.resolve("sub/store.txt.tmp"), temp)
      Files.writeString(temp, "one")
    }
    writeAtomically(path) { Files.writeString(it, "two") }
    assertEquals("two", Files.readString(path))
    assertFalse(Files.exists(folder.resolve("sub/store.txt.tmp")))
  }

  @Test
  fun `a failed write leaves the old file`() {
    val path = folder.resolve("store.txt")
    Files.writeString(path, "old")
    assertThrows<IOException> {
      writeAtomically(path) { temp ->
        Files.writeString(temp, "half")
        throw IOException("disk full")
      }
    }
    assertEquals("old", Files.readString(path))
  }
}
