package com.dont39.pz.modsync.client.download

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class ServerStoreTest {
  @TempDir
  lateinit var folder: Path

  private val file: Path
    get() = folder.resolve("servers.json")

  @Test
  fun `no file remembers no server`() {
    assertFalse(ServerStore(folder).remembered("example.com", 16261))
    assertFalse(Files.exists(file))
  }

  @Test
  fun `a remembered server is kept by address as typed`() {
    val store = ServerStore(folder.resolve("sub"))
    store.remember(" example.com ", 16261)
    store.remember("example.com", 16261)
    store.remember("2001:db8::1", 16261)
    assertTrue(store.remembered("example.com", 16261))
    assertTrue(ServerStore(folder.resolve("sub")).remembered("2001:db8::1", 16261))
    assertFalse(store.remembered("example.com", 16262))
    assertFalse(store.remembered("EXAMPLE.com", 16261))
    assertEquals(
      """{"format":1,"servers":["example.com:16261","2001:db8::1:16261"]}""",
      Files.readString(folder.resolve("sub/servers.json")),
    )
    assertFalse(Files.exists(folder.resolve("sub/servers.json.tmp")))
  }

  @TestFactory
  fun `a corrupt file is an error`() = listOf(
    "",
    "not json",
    """{"format":1}""",
    """{"format":2,"servers":[]}""",
    """{"format":1,"servers":[1]}""",
  ).map { text ->
    DynamicTest.dynamicTest(text.ifEmpty { "(empty)" }) {
      Files.writeString(file, text)
      assertThrows<IllegalStateException> { ServerStore(folder).remembered("example.com", 16261) }
      assertThrows<IllegalStateException> { ServerStore(folder).remember("example.com", 16261) }
      assertEquals(text, Files.readString(file))
    }
  }
}
