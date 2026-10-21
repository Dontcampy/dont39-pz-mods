package com.dont39.pz.modsync.client.rejoin

import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JoinArgsTest {
  private companion object {
    const val HOST = "play.example.com"
    const val PORT = 16261
    val ADDRESS: InetAddress = InetAddress.getByAddress(HOST, byteArrayOf(203.toByte(), 0, 113, 5))
  }

  /** Names resolve from a fixed table; anything else fails as an unknown host. */
  private class FakeHosts(private val names: Map<String, Set<InetAddress>>) : Hosts {
    // what SteamUtils.isValidSteamID accepts
    override fun isSteamId(host: String) =
      host.toBigIntegerOrNull()?.let { it.signum() >= 0 && it.bitLength() <= 64 } ?: false

    override fun resolve(host: String) = names[host] ?: throw UnknownHostException(host)
  }

  private val hosts = FakeHosts(mapOf(HOST to setOf(ADDRESS), "203.0.113.5" to setOf(ADDRESS)))
  private val logged = mutableListOf<String>()

  private fun of(vararg args: String, host: String = HOST, hosts: Hosts = this.hosts) =
    JoinArgs.of(args.toList(), host, PORT, hosts) { logged += it() }

  @Test
  fun `a launch without +connect keeps its arguments`() {
    assertEquals(JoinArgs(null, false, emptySet()), of("-Xmx8g", "--", "-debug"))
    assertEquals(JoinArgs(null, false, emptySet()), of())
  }

  @Test
  fun `a +connect to this server is kept`() {
    assertEquals(JoinArgs("$HOST:$PORT", true, emptySet()), of("-debug", "+connect", "$HOST:$PORT", "+password", "pw"))
    assertEquals(JoinArgs(" $HOST :$PORT", true, emptySet()), of("+connect", " $HOST :$PORT", host = " $HOST"))
    // the main menu's split drops the empty part after the last ':'
    assertEquals(JoinArgs("$HOST:$PORT:", true, emptySet()), of("+connect", "$HOST:$PORT:"))
  }

  @Test
  fun `a +connect to this server's address is kept`() {
    assertEquals(JoinArgs("203.0.113.5:$PORT", true, emptySet()), of("+connect", "203.0.113.5:$PORT"))
    assertTrue(logged.isEmpty())
  }

  @Test
  fun `a +connect elsewhere is left out with every +password`() {
    val other = FakeHosts(mapOf(HOST to setOf(ADDRESS), "other.example.com" to setOf(InetAddress.getLoopbackAddress())))
    assertEquals(
      JoinArgs("other.example.com:$PORT", false, setOf(1, 2, 3, 4, 6, 7)),
      of("-debug", "+password", "a", "+connect", "other.example.com:$PORT", "-nosound", "+password", "b", hosts = other),
    )
  }

  @Test
  fun `a +connect to another port is elsewhere`() {
    assertEquals(JoinArgs("$HOST:16262", false, setOf(0, 1)), of("+connect", "$HOST:16262"))
  }

  @Test
  fun `a co-op host's Steam ID is elsewhere`() {
    assertEquals(JoinArgs("76561198000000000:$PORT", false, setOf(0, 1)), of("+connect", "76561198000000000:$PORT"))
    assertEquals(JoinArgs("76561198000000000", false, setOf(0, 1)), of("+connect", "76561198000000000"))
  }

  @Test
  fun `a target the main menu does not join is elsewhere`() {
    assertEquals(JoinArgs(HOST, false, setOf(0, 1)), of("+connect", HOST))
    assertEquals(JoinArgs("$HOST:$PORT:1", false, setOf(0, 1)), of("+connect", "$HOST:$PORT:1"))
    assertEquals(JoinArgs(":$PORT", false, setOf(0, 1)), of("+connect", ":$PORT"))
    assertEquals(JoinArgs("$HOST:port", false, setOf(0, 1)), of("+connect", "$HOST:port"))
  }

  @Test
  fun `JVM options before the game's arguments count in the indices`() {
    assertEquals(
      JoinArgs("other:$PORT", false, setOf(3, 4)),
      of("-agentlib:zbNative", "-Dx=+connect", "--", "+connect", "other:$PORT", "-debug"),
    )
  }

  @Test
  fun `+password without +connect stays`() {
    assertEquals(JoinArgs(null, false, emptySet()), of("+password", "pw", "-debug"))
  }

  @Test
  fun `the last +connect wins`() {
    assertEquals(JoinArgs("$HOST:$PORT", true, emptySet()), of("+connect", "other:1", "+connect", "$HOST:$PORT"))
    assertEquals(JoinArgs("other:1", false, setOf(0, 1, 2, 3)), of("+connect", "$HOST:$PORT", "+connect", "other:1"))
  }

  @Test
  fun `a +connect without a value joins nothing`() {
    assertEquals(JoinArgs(null, false, emptySet()), of("-debug", "+connect"))
    assertEquals(JoinArgs("$HOST:$PORT", true, emptySet()), of("+connect", "$HOST:$PORT", "+connect"))
  }

  @Test
  fun `an argument the game takes as a value is not read as +connect`() {
    // the game takes "+connect" as the target and goes on after it
    assertEquals(JoinArgs("+connect", false, setOf(0, 1)), of("+connect", "+connect", "$HOST:$PORT"))
    assertEquals(JoinArgs(null, false, emptySet()), of("-modfolders", "+connect", "other:1"))
  }

  @Test
  fun `a host that does not resolve is compared by name only, and logged`() {
    assertEquals(JoinArgs("unknown.example.com:$PORT", false, setOf(0, 1)), of("+connect", "unknown.example.com:$PORT"))
    assertEquals(1, logged.size)
    assertTrue("unknown.example.com" in logged.single())
    logged.clear()
    assertEquals(JoinArgs("unknown:$PORT", true, emptySet()), of("+connect", "unknown:$PORT", host = "unknown"))
    assertTrue(logged.isEmpty())
  }

  /** Records every lookup, and fails none: [names] or an unknown host. */
  private class CountingHosts(private val names: Map<String, Set<InetAddress>> = emptyMap()) : Hosts {
    val resolved = mutableListOf<String>()

    override fun isSteamId(host: String) = false

    override fun resolve(host: String): Set<InetAddress> {
      resolved += host
      return names[host] ?: throw UnknownHostException(host)
    }
  }

  @Test
  fun `two IP literals compare without a lookup`() {
    val counting = CountingHosts()
    fun of(target: String, host: String) = of("+connect", "$target:$PORT", host = host, hosts = counting)
    assertEquals(JoinArgs("203.0.113.05:$PORT", true, emptySet()), of("203.0.113.05", "203.0.113.5"))
    assertEquals(JoinArgs("203.0.113.6:$PORT", false, setOf(0, 1)), of("203.0.113.6", "203.0.113.5"))
    assertEquals(JoinArgs("127.0.0.1:$PORT", false, setOf(0, 1)), of("127.0.0.1", "::1"))
    assertEquals(emptyList<String>(), counting.resolved)
    assertTrue(logged.isEmpty())
  }

  @Test
  fun `a literal against a name resolves only the name`() {
    val counting = CountingHosts(mapOf(HOST to setOf(ADDRESS)))
    assertEquals(JoinArgs("203.0.113.5:$PORT", true, emptySet()), of("+connect", "203.0.113.5:$PORT", hosts = counting))
    assertEquals(listOf(HOST), counting.resolved)
  }

  @Test
  fun `a dotted host out of range is a name`() {
    val counting = CountingHosts()
    assertEquals(
      JoinArgs("999.0.113.5:$PORT", false, setOf(0, 1)),
      of("+connect", "999.0.113.5:$PORT", host = "203.0.113.5", hosts = counting),
    )
    assertEquals(listOf("999.0.113.5"), counting.resolved)
  }

  @Test
  fun `a lookup needing no DNS is done at once`() {
    val counting = CountingHosts()
    assertEquals(JoinArgs(null, false, emptySet()), lookup(listOf("-debug"), counting).result)
    assertEquals(JoinArgs("$HOST:$PORT", true, emptySet()), lookup(listOf("+connect", "$HOST:$PORT"), counting).result)
    assertEquals(JoinArgs("$HOST:1", false, setOf(0, 1)), lookup(listOf("+connect", "$HOST:1"), counting).result)
    assertEquals(emptyList<String>(), counting.resolved)
  }

  @Test
  fun `a lookup that resolves a name runs off the calling thread`() {
    val release = CountDownLatch(1)
    val blocking = object : Hosts {
      override fun isSteamId(host: String) = false

      override fun resolve(host: String): Set<InetAddress> {
        check(release.await(10, TimeUnit.SECONDS)) { "the test did not release the lookup" }
        return setOf(ADDRESS)
      }
    }
    val lookup = lookup(listOf("+connect", "203.0.113.5:$PORT"), blocking)
    assertNull(lookup.result)
    release.countDown()
    val until = System.currentTimeMillis() + 10_000
    while (lookup.result == null && System.currentTimeMillis() < until) {
      Thread.sleep(5)
    }
    assertEquals(JoinArgs("203.0.113.5:$PORT", true, emptySet()), lookup.result)
  }

  private fun lookup(args: List<String>, hosts: Hosts) = JoinArgs.Lookup(args, HOST, PORT, hosts) { logged += it() }
}
