package com.dont39.pz.modsync.client.rejoin

import com.dont39.pz.modsync.client.java.JavaMod
import com.dont39.pz.modsync.client.java.Launch
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class RestartTest {
  /** A launch with [arguments] whose restart records what it left out, or fails with [failure]. */
  private class FakeLaunch(private val arguments: List<String>, private val failure: Exception? = null) : Launch {
    val restarts = mutableListOf<Set<Int>>()

    override fun arguments() = arguments

    override fun relaunchUnavailable(): String? = failure?.message

    override fun relaunch(omitted: Set<Int>) {
      failure?.let { throw it }
      restarts += omitted
    }

    override fun current(): Map<String, Path> = unused()

    override fun pending(): Map<String, Path> = unused()

    override fun request(modsByItem: Map<String, Path>) = unused()

    override fun javaMods(modsFolder: Path): List<JavaMod> = unused()

    override fun servingJar(pkg: String): Path? = unused()

    override fun mainMenuMods(): Set<String> = unused()

    private fun unused(): Nothing = throw UnsupportedOperationException("a restart does not ask this")
  }

  private object NoNames : Hosts {
    override fun isSteamId(host: String) = false

    override fun resolve(host: String): Set<InetAddress> = throw UnknownHostException(host)
  }

  private val logged = mutableListOf<String>()

  private fun restart(launch: Launch): String? {
    val args = JoinArgs.of(launch.arguments(), "play.example.com", 16261, NoNames) { logged += it() }
    return restartFor(launch, args) { logged += it() }
  }

  @Test
  fun `the restart keeps a +connect to this server`() {
    val launch = FakeLaunch(listOf("-debug", "+connect", "play.example.com:16261", "+password", "pw"))
    assertNull(restart(launch))
    assertEquals(listOf(emptySet<Int>()), launch.restarts)
  }

  @Test
  fun `the restart leaves out a +connect elsewhere`() {
    val launch = FakeLaunch(listOf("+password", "pw", "-debug", "+connect", "other.example.com:16261"))
    assertNull(restart(launch))
    assertEquals(listOf(setOf(0, 1, 3, 4)), launch.restarts)
  }

  @Test
  fun `a refused restart returns its reason and the game runs on`() {
    val launch = FakeLaunch(listOf("-debug"), IOException("the restart helper did not answer within 10 s"))
    assertEquals("the restart helper did not answer within 10 s", restart(launch))
    assertEquals(emptyList<Set<Int>>(), launch.restarts)
    assertEquals(1, logged.count { "cannot restart" in it })
  }

  @Test
  fun `an error other than a refusal is thrown`() {
    val launch = FakeLaunch(listOf("-debug"), IllegalArgumentException("no argument 5 to leave out"))
    assertThrows(IllegalArgumentException::class.java) { restart(launch) }
  }
}
