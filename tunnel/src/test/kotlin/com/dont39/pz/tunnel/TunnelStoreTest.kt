package com.dont39.pz.tunnel

import com.dont39.pz.tunnel.TunnelStore.Entry
import com.dont39.pz.tunnel.TunnelStore.Mode
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

/**
 * The store's format, pinned by the fixtures under `store/` that `Dont39AllInOne_TunnelShared.lua` parses the same
 * way: a valid store, lines both parsers refuse, and the released TCP-only store.
 */
class TunnelStoreTest {
  private companion object {
    const val STORE = "Dont39AllInOne_Tunnel.txt"
    const val RELEASED = "Dont39AllInOne_TcpTunnel.txt"
  }

  @TempDir
  lateinit var folder: Path

  private fun fixture(name: String): String =
    checkNotNull(javaClass.getResource("/store/$name")) { "no fixture $name" }.readText()

  private fun lines(name: String) = fixture(name).lines().filter { it.isNotEmpty() }

  private fun get(host: String, port: Int) = TunnelStore.get(host, port, folder)

  private fun set(
    host: String,
    port: String,
    mode: String,
    tcp: String,
    kcp: String,
    old: Pair<String, String>? = null,
  ) = TunnelStore.set(old?.first, old?.second, host, port, mode, tcp, kcp, folder)

  private fun stored(): List<String> = Files.readAllLines(folder.resolve(STORE))

  @Test
  fun `the fixture parses as both parsers read it`() {
    Files.writeString(folder.resolve(STORE), fixture(STORE))
    // the last line of an address wins
    assertEquals(Entry(Mode.KCP, 16300, 16301), get("example.com", 16261))
    assertEquals(Entry(Mode.KCP, 16300, 16301), get("10.0.0.5", 16261))
    assertEquals(Entry(Mode.OFF, 16300, 16301), get("spaced.example.org", 16261))
    assertEquals(Entry(Mode.KCP, 0, 16301), get("2001:db8::1", 16261))
    assertEquals(Entry(Mode.OFF, 0, 0), get("example.com", 16262))
    // as typed: the join only sees the address
    assertNull(get("EXAMPLE.com", 16261))
    assertNull(get("example.com", 16263))
  }

  @TestFactory
  fun `a malformed line is an error`() = lines("invalid-lines.txt").map { line ->
    DynamicTest.dynamicTest(line) {
      Files.writeString(folder.resolve(STORE), "example.org:16261=tcp,16300,0\n$line\n")
      assertThrows<IllegalStateException> { get("example.org", 16261) }
    }
  }

  @Test
  fun `what is set is written and read back`() {
    set("example.com", "16261", "tcp", "16300", "")
    set(" 10.0.0.5 ", "16261", "kcp", " 16300 ", "16301")
    set("example.com", "16262", "off", "16300", "0")
    assertEquals(
      listOf("example.com:16261=tcp,16300,0", "10.0.0.5:16261=kcp,16300,16301", "example.com:16262=off,16300,0"),
      stored(),
    )
    assertEquals(Entry(Mode.KCP, 16300, 16301), get("10.0.0.5", 16261))
    assertFalse(Files.exists(folder.resolve("$STORE.tmp")))
  }

  @Test
  fun `an edit moves the tunnel with the entry, and off without ports removes it`() {
    set("example.com", "16261", "tcp", "16300", "")
    set("example.net", "16261", "kcp", "16300", "16301", old = "example.com" to "16261")
    assertEquals(listOf("example.net:16261=kcp,16300,16301"), stored())
    set("example.net", "16261", "off", "", "", old = "example.net" to "16261")
    assertEquals(emptyList<String>(), stored())
  }

  @Test
  fun `a tunnel without its port is refused`() {
    assertThrows<IllegalArgumentException> { set("example.com", "16261", "tcp", "", "16301") }
    assertThrows<IllegalArgumentException> { set("example.com", "16261", "kcp", "16300", "70000") }
    assertThrows<IllegalArgumentException> { set("example.com", "16261", "udp", "16300", "") }
    assertFalse(Files.exists(folder.resolve(STORE)))
  }

  @Test
  fun `the released store is migrated and kept`() {
    Files.writeString(folder.resolve(RELEASED), fixture(RELEASED))
    assertEquals(Entry(Mode.TCP, 16300, 0), get("example.com", 16261))
    assertEquals(Entry(Mode.TCP, 16400, 0), get("10.0.0.5", 16261))
    assertEquals(listOf("example.com:16261=tcp,16300,0", "10.0.0.5:16261=tcp,16400,0"), stored())
    // never written or deleted, so an older mod version still finds it
    assertEquals(fixture(RELEASED), Files.readString(folder.resolve(RELEASED)))
  }

  @Test
  fun `the new store wins over the released one`() {
    Files.writeString(folder.resolve(RELEASED), fixture(RELEASED))
    set("example.com", "16261", "kcp", "16300", "16301")
    assertEquals(Entry(Mode.KCP, 16300, 16301), get("example.com", 16261))
    Files.writeString(folder.resolve(RELEASED), "example.com:16261=16500\n")
    assertEquals(Entry(Mode.KCP, 16300, 16301), get("example.com", 16261))
  }

  @TestFactory
  fun `a malformed released line is an error`() = lines("invalid-released-lines.txt").map { line ->
    DynamicTest.dynamicTest(line) {
      Files.writeString(folder.resolve(RELEASED), "$line\n")
      assertThrows<IllegalStateException> { get("example.com", 16261) }
    }
  }

  @Test
  fun `no store is no tunnel`() {
    assertNull(get("example.com", 16261))
  }
}
