package com.dont39.pz.modsync.client

import com.dont39.pz.modsync.SERVER_FILES
import com.dont39.pz.modsync.client.JoinSession.Answer
import com.dont39.pz.modsync.client.JoinSession.Phase
import com.dont39.pz.modsync.client.JoinSession.Step
import com.dont39.pz.modsync.client.download.Cache
import com.dont39.pz.modsync.client.swap.SwapEntry
import com.dont39.pz.modsync.server.HttpService
import com.dont39.pz.modsync.server.ManifestBuilder
import com.dont39.pz.modsync.writeItem
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** The join's state machine as the `CheckMods` hold polls it, against a server on 127.0.0.1. */
class JoinSessionTest {
  private companion object {
    const val ID = 2409333430
    const val TIMESTAMP = 1_760_000_000L
    const val CHANGED = "mods/Alpha/42/media/lua/client/Alpha.lua"
    const val GAME_PORT = 16261
    const val WAIT_MILLIS = 10_000L
  }

  @TempDir
  lateinit var folder: Path

  private lateinit var serverItem: Path
  private lateinit var service: HttpService
  private lateinit var cache: Cache
  private lateinit var install: Path

  @BeforeEach
  fun start() {
    serverItem = writeItem(folder.resolve("server/$ID"), SERVER_FILES)
    serve(serverItem)
    cache = Cache(folder.resolve("client/ModSync"))
    install = writeItem(folder.resolve("client/workshop/$ID"), SERVER_FILES + (CHANGED to "print('alpha 1')\n"))
  }

  @AfterEach
  fun stop() {
    service.close()
  }

  private fun serve(item: Path) {
    val served = ManifestBuilder.build(listOf(ManifestBuilder.Source(ID, TIMESTAMP, item)))
    val events = object : HttpService.Events {
      override fun ioFailed(message: String) {}

      override fun failed(t: Throwable) = throw AssertionError(t)
    }
    service = HttpService.start(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), served.json, served.files, events)
  }

  private fun session(port: Int = service.port, timestamp: Long = TIMESTAMP): JoinSession =
    JoinSession("127.0.0.1", GAME_PORT, Location.base("127.0.0.1", port), cache, { install.toString() }, {}).apply {
      mark(JoinSession.Marked(ID, timestamp, "Alpha"))
    }

  /** Polls as the hold does each frame, until a step other than [Step.Hold] or the dialog's question. */
  private fun run(session: JoinSession): Step {
    val until = System.currentTimeMillis() + WAIT_MILLIS
    while (System.currentTimeMillis() < until) {
      val step = session.poll()
      if (step != Step.Hold || session.phase is Phase.Confirm) {
        return step
      }
      Thread.sleep(5)
    }
    throw AssertionError("the join did not move on")
  }

  @Test
  fun `a first join asks, downloads the changed file, and swaps to the tree`() {
    val session = session()
    assertEquals(Step.Hold, run(session))
    val confirm = session.phase as Phase.Confirm
    assertEquals(1, confirm.prepared.downloadFiles)
    session.answer(Answer.REMEMBER)
    val ready = run(session) as Step.Ready
    val entry = ready.entries.single()
    assertEquals(SwapEntry.normalize(install.resolve("mods").toString()), entry.installMods)
    val tree = cache.tree("127.0.0.1", GAME_PORT, confirm.prepared.builds.single().plan.item)
    assertEquals(tree.resolve("mods").toAbsolutePath().normalize(), entry.treeMods)
    assertEquals("print('alpha 2')\n", Files.readString(tree.resolve(CHANGED)))
    assertTrue(cache.servers.remembered("127.0.0.1", GAME_PORT))
  }

  @Test
  fun `a second join reuses the tree without asking`() {
    session().also { run(it); it.answer(Answer.ACCEPT); run(it) as Step.Ready }
    val second = session()
    val ready = run(second) as Step.Ready
    assertEquals(1, ready.entries.size)
    assertEquals(Phase.Over, second.phase)
  }

  @Test
  fun `a remembered server is not asked`() {
    cache.servers.remember("127.0.0.1", GAME_PORT)
    assertTrue(run(session()) is Step.Ready)
  }

  @Test
  fun `declining stops the join`() {
    val session = session()
    run(session)
    session.answer(Answer.DECLINE)
    assertEquals("Failed_Declined", (run(session) as Step.Stop).key)
  }

  @Test
  fun `another version on the server stops the join`() {
    val stop = run(session(timestamp = 1)) as Step.Stop
    assertEquals("Failed", stop.key)
    assertTrue("version $TIMESTAMP" in stop.args.single(), stop.args.single())
  }

  @Test
  fun `a changed jar stops the join, also with a ready tree`() {
    Files.writeString(install.resolve("mods/Alpha/42/media/java/alpha.jar"), "jar v2")
    val stop = run(session()) as Step.Stop
    assertEquals("Failed_Jar", stop.key)
    assertEquals(listOf("\"Alpha\" ($ID)", "mods/Alpha/42/media/java/alpha.jar"), stop.args)

    Files.writeString(install.resolve("mods/Alpha/42/media/java/alpha.jar"), "jar v1")
    cache.servers.remember("127.0.0.1", GAME_PORT)
    assertTrue(run(session()) is Step.Ready)
    Files.writeString(install.resolve("mods/Alpha/42/media/java/alpha.jar"), "jar v2")
    assertEquals("Failed_Jar", (run(session()) as Step.Stop).key)
  }

  @Test
  fun `an item carrying the loader is refused`() {
    service.close()
    serve(writeItem(folder.resolve("server2/$ID"), mapOf("mods/Zabric/42/mod.info" to "id=Zabric\n")))
    val stop = run(session()) as Step.Stop
    assertEquals("Failed_Refused", stop.key)
    assertEquals("Zabric", stop.args[1])
  }

  @Test
  fun `no answer from the server stops the join with the network reason`() {
    val closed = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
    val stop = run(session(port = closed)) as Step.Stop
    assertEquals("Failed_Network", stop.key)
    assertEquals("127.0.0.1:$closed", stop.args[0])
  }

  @Test
  fun `cancelling ends the session`() {
    val session = session()
    session.poll()
    session.cancel()
    assertEquals(Phase.Over, session.phase)
  }

  @Test
  fun `refusals by Workshop id and mod folder`() {
    assertEquals("ZombieBuddy", Refusal.of(3619862853L, emptySet()))
    assertEquals("Dont39 All-in-One", Refusal.of(1L, setOf("Other", "dont39allinone")))
    assertNull(Refusal.of(1L, setOf("Other")))
  }
}
