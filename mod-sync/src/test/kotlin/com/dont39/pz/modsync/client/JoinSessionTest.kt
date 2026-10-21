package com.dont39.pz.modsync.client

import com.dont39.pz.modsync.SERVER_FILES
import com.dont39.pz.modsync.client.JoinSession.Answer
import com.dont39.pz.modsync.client.JoinSession.Phase
import com.dont39.pz.modsync.client.JoinSession.Step
import com.dont39.pz.modsync.client.download.Cache
import com.dont39.pz.modsync.client.java.Launch
import com.dont39.pz.modsync.client.swap.SwapEntry
import com.dont39.pz.modsync.jar
import com.dont39.pz.modsync.server.HttpService
import com.dont39.pz.modsync.server.ManifestBuilder
import com.dont39.pz.modsync.writeBytes
import com.dont39.pz.modsync.writeItem
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
    const val JAR = "mods/Alpha/42/media/java/alpha.jar"
    const val JAVA_INFO = "name=Alpha\nid=Alpha\njavaJarFile=media/java/alpha.jar\njavaPkgName=com.example.alpha\n"
    const val GAME_PORT = 16261
    const val WAIT_MILLIS = 10_000L
  }

  /** This launch as Zabric would report it; a request becomes [pending]. */
  private class FakeLaunch : Launch {
    var current: Map<String, Path> = emptyMap()
    var pending: Map<String, Path> = emptyMap()
    var requests = 0
    val serving = mutableMapOf<String, Path>()
    var mainMenuMods: Set<String> = emptySet()

    override fun current() = current

    override fun pending() = pending

    override fun request(modsByItem: Map<String, Path>) {
      requests++
      pending = modsByItem
    }

    override fun servingJar(pkg: String): Path? = serving[pkg]

    override fun mainMenuMods() = mainMenuMods

    override fun versionFolder(modFolder: Path) = "42"

    /** The next launch, which applies the request. */
    fun restart() {
      current = pending
      pending = emptyMap()
      serving.clear()
    }
  }

  @TempDir
  lateinit var folder: Path

  private lateinit var serverItem: Path
  private lateinit var service: HttpService
  private lateinit var cache: Cache
  private lateinit var install: Path
  private val launch = FakeLaunch()

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
    JoinSession("127.0.0.1", GAME_PORT, Location.base("127.0.0.1", port), cache, { install.toString() }, launch, {})
      .apply { mark(JoinSession.Marked(ID, timestamp, "Alpha")) }

  /** The server's Alpha has another jar than the client's, by default. */
  private fun javaItem(
    serverJar: ByteArray = jar(entry = "v2"),
    clientJar: ByteArray = jar(entry = "v1"),
    clientInfo: String = JAVA_INFO,
    serverInfo: String = JAVA_INFO,
  ) {
    service.close()
    val server = writeItem(folder.resolve("java-server/$ID"), SERVER_FILES + ("mods/Alpha/42/mod.info" to serverInfo))
    writeBytes(server.resolve(JAR), serverJar)
    serve(server)
    install = writeItem(folder.resolve("java-client/$ID"), SERVER_FILES + ("mods/Alpha/42/mod.info" to clientInfo))
    writeBytes(install.resolve(JAR), clientJar)
  }

  private fun treeMods(): Path {
    val source = ManifestBuilder.Source(ID, TIMESTAMP, folder.resolve("java-server/$ID"))
    val served = ManifestBuilder.build(listOf(source))
    val item = served.manifest.item(ID, TIMESTAMP)
    return cache.tree("127.0.0.1", GAME_PORT, item).resolve("mods").toAbsolutePath().normalize()
  }

  /** Asks, as every join with a jar change does, and answers. */
  private fun confirmed(session: JoinSession, answer: Answer = Answer.ACCEPT): Step {
    assertEquals(Step.Hold, run(session))
    val confirm = session.phase as Phase.Confirm
    assertTrue(confirm.prepared.listed.single().jarChanges.isNotEmpty())
    session.answer(answer)
    return run(session)
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
  fun `a changed jar asks, downloads, requests the override and stops for a restart`() {
    javaItem()
    launch.pending = mapOf("1" to folder.resolve("other/mods"))
    val stop = confirmed(session()) as Step.Stop
    assertEquals("Failed_Restart", stop.key)
    assertTrue(stop.restart)
    assertEquals(listOf("\"Alpha\" ($ID)"), stop.args)
    // another server's pending request stays
    assertEquals(mapOf("1" to folder.resolve("other/mods"), "$ID" to treeMods()), launch.pending)
    assertTrue(Files.isRegularFile(treeMods().resolve("Alpha/42/media/java/alpha.jar")))
  }

  @Test
  fun `a remembered server still asks for a jar change, also for a ready tree`() {
    javaItem()
    cache.servers.remember("127.0.0.1", GAME_PORT)
    assertEquals("Failed_Restart", (confirmed(session(), Answer.REMEMBER) as Step.Stop).key)
    // a next launch without the override: the ready tree still asks, downloading nothing
    launch.pending = emptyMap()
    val session = session()
    assertEquals(Step.Hold, run(session))
    val prepared = (session.phase as Phase.Confirm).prepared
    assertEquals(0, prepared.downloadFiles)
    assertTrue(prepared.listed.single() is JoinSession.Work.Reused)
  }

  @Test
  fun `the launch after the restart joins with the tree without asking`() {
    javaItem()
    confirmed(session())
    launch.restart()
    val ready = run(session()) as Step.Ready
    assertEquals(treeMods(), ready.entries.single().treeMods)
    assertEquals("com.example.alpha", ready.expected.single().pkg)
    assertEquals(1, launch.requests)

    // the startup pass loaded it from the tree
    launch.serving["com.example.alpha"] = treeMods().resolve("Alpha/42/media/java/alpha.jar").toRealPath()
    launch.mainMenuMods = setOf("Alpha")
    assertTrue(run(session()) is Step.Ready)
  }

  @Test
  fun `a jar of the item served from elsewhere stops the join`() {
    javaItem()
    confirmed(session())
    launch.restart()
    launch.serving["com.example.alpha"] = install.resolve(JAR)
    val stop = run(session()) as Step.Stop
    assertEquals("Failed_NotServed", stop.key)
    assertEquals(listOf("Alpha", install.resolve(JAR).toString()), stop.args)
    assertFalse(stop.restart)
  }

  @Test
  fun `a mod of the main menu whose jar the game did not load stops the join`() {
    javaItem()
    confirmed(session())
    launch.restart()
    launch.mainMenuMods = setOf("Alpha")
    val stop = run(session()) as Step.Stop
    assertEquals("Failed_NotLoaded", stop.key)
    assertEquals(listOf("Alpha", "\"Alpha\" ($ID)"), stop.args)
  }

  @Test
  fun `a mod only another mod's require pulls in, with nothing served, goes on to the join's pass`() {
    javaItem()
    confirmed(session())
    launch.restart()
    // Beta requires Alpha: the game loads Alpha, the loader never looked at it
    launch.mainMenuMods = setOf("Beta")
    val ready = run(session()) as Step.Ready
    assertEquals("Alpha", ready.expected.single().id)
  }

  @Test
  fun `an item overridden with another tree takes the restart path again`() {
    javaItem()
    launch.current = mapOf("$ID" to folder.resolve("items/older/mods"))
    assertEquals("Failed_Restart", (confirmed(session()) as Step.Stop).key)
    assertEquals(mapOf("$ID" to treeMods()), launch.pending)
  }

  @Test
  fun `a preload mod in the client's copy is refused before any download`() {
    javaItem(clientInfo = "javaPreload=true\n$JAVA_INFO")
    val stop = run(session()) as Step.Stop
    assertEquals("Failed_Preload", stop.key)
    assertEquals(listOf("Alpha", "\"Alpha\" ($ID)"), stop.args)
    assertFalse(Files.exists(treeMods()))
    assertEquals(0, launch.requests)
  }

  @Test
  fun `a preload mod in the server's copy is refused after the download, without a request`() {
    javaItem(serverJar = jar("Zabric-PreLaunch" to "com.example.alpha.Pre", entry = "v2"))
    assertEquals("Failed_Preload", (confirmed(session()) as Step.Stop).key)
    assertTrue(Files.isDirectory(treeMods()))
    assertEquals(0, launch.requests)
  }

  @Test
  fun `a jar no mod names still takes the restart path`() {
    javaItem(clientInfo = "name=Alpha\nid=Alpha\n", serverInfo = "name=Alpha\nid=Alpha\n")
    assertEquals("Failed_Restart", (confirmed(session()) as Step.Stop).key)
    launch.restart()
    val ready = run(session()) as Step.Ready
    assertEquals(emptyList<Any>(), ready.expected)
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
