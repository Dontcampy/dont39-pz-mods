package com.dont39.pz.modsync.client.rejoin

import com.dont39.pz.modsync.client.rejoin.RejoinStore.Rejoin
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class RejoinStoreTest {
  @TempDir
  lateinit var folder: Path

  private val file: Path
    get() = folder.resolve("sub/rejoin.json")

  private val store: RejoinStore
    get() = RejoinStore(folder.resolve("sub"))

  @Test
  fun `no file has nothing to rejoin`() {
    assertNull(store.read())
    store.drop()
    assertFalse(Files.exists(file))
  }

  @Test
  fun `the last record wins and reads back`() {
    store.write(Rejoin("example.com", 16261, "alice", "Example", 1L))
    val last = Rejoin("2001:db8::1", 16262, "bob", "Other \"server\"", 1_760_000_000_000L)
    store.write(last)
    assertEquals(last, store.read())
    val json = """{"format":1,"host":"2001:db8::1","port":16262,"user":"bob","name":"Other \"server\"",""" +
      """"written":1760000000000}"""
    assertEquals(json, Files.readString(file))
    assertFalse(Files.exists(folder.resolve("sub/rejoin.json.tmp")))
  }

  @Test
  fun `dropping removes it`() {
    store.write(Rejoin("example.com", 16261, "alice", "Example", 1L))
    store.drop()
    assertNull(store.read())
    assertFalse(Files.exists(file))
  }

  @TestFactory
  fun `a corrupt file is an error`() = listOf(
    "",
    "not json",
    """{"format":1,"host":"example.com"}""",
    """{"format":2,"host":"example.com","port":16261,"user":"alice","name":"Example","written":1}""",
  ).map { text ->
    DynamicTest.dynamicTest(text.ifEmpty { "(empty)" }) {
      Files.createDirectories(file.parent)
      Files.writeString(file, text)
      assertThrows<IllegalStateException> { store.read() }
      assertEquals(text, Files.readString(file))
    }
  }
}
