package com.dont39.pz.modsync

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

/** Server and client sides end to end on 127.0.0.1. */
class HttpSyncTest {
  private companion object {
    const val ID = 2409333430
    const val TIMESTAMP = 1_760_000_000L
    const val CHANGED = "mods/Alpha/42/media/lua/client/Alpha.lua"
    const val WAIT_MILLIS = 10_000L
  }

  @TempDir
  lateinit var folder: Path

  private lateinit var serverItem: Path
  private lateinit var served: Manifest.Served
  private lateinit var service: HttpService
  private lateinit var cache: Cache
  private val ioFailures: MutableList<String> = Collections.synchronizedList(mutableListOf())
  private val failures: MutableList<Throwable> = Collections.synchronizedList(mutableListOf())

  private val item: ManifestItem
    get() = served.manifest.items.single()

  private val base: URI
    get() = Trailer.base("127.0.0.1", service.port)

  @BeforeEach
  fun start() {
    serverItem = writeItem(folder.resolve("server/$ID"), SERVER_FILES)
    served = Manifest.build(listOf(Manifest.Source(ID, TIMESTAMP, serverItem)))
    val events = object : HttpService.Events {
      override fun ioFailed(message: String) {
        ioFailures += message
      }

      override fun failed(t: Throwable) {
        failures += t
      }
    }
    val address = InetSocketAddress(InetAddress.getLoopbackAddress(), 0)
    service = HttpService.start(address, served.json, served.files, events)
    cache = Cache(folder.resolve("client/ModSync"))
  }

  @AfterEach
  fun stop() {
    service.close()
    assertEquals(emptyList<Throwable>(), failures)
  }

  /** A client copy with [CHANGED] at another version. */
  private fun localCopy(): Path =
    writeItem(folder.resolve("client/workshop/$ID"), SERVER_FILES + (CHANGED to "print('alpha 1')\n"))

  /** Plans and builds the tree on a worker, as a join does. */
  private fun join(fetcher: Fetcher = Fetcher(base), local: Path = localCopy()): Worker.Outcome<Plan>? {
    val worker = Worker("test join") { progress ->
      val item = fetcher.manifest().item(ID, TIMESTAMP)
      val copy = LocalCopy.scan(local, item, progress)
      val plan = Plan.of(item, copy)
      progress.begin(plan.download.size, plan.downloadBytes)
      cache.build(cache.tree("127.0.0.1", 16261, item), plan, copy, fetcher, progress)
      plan
    }.start()
    lastProgress = worker.progress
    return worker.await(WAIT_MILLIS)
  }

  private var lastProgress: Progress? = null

  private val tree: Path
    get() = cache.tree("127.0.0.1", 16261, item)

  private fun failure(outcome: Worker.Outcome<Plan>?): Throwable = (outcome as Worker.Outcome.Failed).error

  @Test
  fun `the service's threads do not keep the JVM alive`() {
    assertFalse(Thread.currentThread().isDaemon)
    val dispatchers = Thread.getAllStackTraces().keys.filter { it.name == "HTTP-Dispatcher" }
    assertTrue(dispatchers.isNotEmpty())
    assertTrue(dispatchers.all { it.isDaemon }, "a dispatcher thread is not a daemon")
  }

  @Test
  fun `the manifest arrives as served`() {
    assertEquals(served.manifest, Fetcher(base).manifest())
  }

  @Test
  fun `only the changed file is downloaded, and the tree is reused`() {
    val plan = (join() as Worker.Outcome.Done).value
    assertEquals(listOf(CHANGED), plan.download.map { it.path })
    val progress = checkNotNull(lastProgress)
    assertEquals(1, progress.filesDone)
    assertEquals(1, progress.totalFiles)
    assertEquals(plan.downloadBytes, progress.bytesDone)
    assertTrue(cache.ready(tree, item))
    item.files.forEach { file ->
      assertEquals(Files.readString(serverItem.resolve(file.path)), Files.readString(tree.resolve(file.path)))
    }
    assertEquals(tree, folder.resolve("client/ModSync/items/127.0.0.1_16261/$ID/$TIMESTAMP/$ID"))
    assertFalse(Files.exists(tree.resolveSibling("$ID.staging")))
  }

  @Test
  fun `a tree that differs from the manifest is not reused`() {
    join()
    Files.writeString(tree.resolve("mods/Alpha/42/extra.lua"), "x")
    assertFalse(cache.ready(tree, item))
    Files.delete(tree.resolve("mods/Alpha/42/extra.lua"))
    assertTrue(cache.ready(tree, item))
    Files.writeString(tree.resolve(CHANGED), "print('alpha 22')\n")
    assertFalse(cache.ready(tree, item))
    // a new build replaces it
    join()
    assertTrue(cache.ready(tree, item))
    assertFalse(cache.ready(tree, item.copy(timestamp = 1)))
    assertFalse(cache.ready(folder.resolve("nothing"), item))
  }

  @Test
  fun `a corrupted file stops the join, and the next join recovers`() {
    val original = Files.readString(serverItem.resolve(CHANGED))
    Files.writeString(serverItem.resolve(CHANGED), original.replace('2', '3'))
    assertTrue("SHA-256" in failure(join()).message!!)
    assertFalse(cache.ready(tree, item))
    assertFalse(Files.exists(tree))

    Files.writeString(serverItem.resolve(CHANGED), original.dropLast(1))
    assertTrue("has 16 of the 17 bytes" in failure(join()).message!!)

    Files.writeString(serverItem.resolve(CHANGED), "$original--")
    assertTrue("larger than" in failure(join()).message!!)

    Files.writeString(serverItem.resolve(CHANGED), original)
    assertTrue(join() is Worker.Outcome.Done)
    assertTrue(cache.ready(tree, item))
  }

  @Test
  fun `a changed local copy fails its own check`() {
    val copy = localCopy()
    val target = folder.resolve("check/x")
    val file = item.files.first { it.path == "mods/Alpha/42/mod.info" }
    Files.writeString(copy.resolve(file.path), "name=Alphb\nid=Alpha\n")
    val e = assertThrows<SyncException> {
      Files.newInputStream(copy.resolve(file.path)).use { writeVerified(it, target, file, "your copy", null) }
    }
    assertTrue("from your copy" in e.message!!)
    assertFalse(Files.exists(target))
    // nor its temporary file
    assertEquals(0L, Files.list(target.parent).use { it.count() })
  }

  @Test
  fun `a file the server does not list is 404`() {
    val missing = ManifestFile("mods/a/b", 1, "0".repeat(64))
    val e = assertThrows<SyncException> { Fetcher(base).download(missing, folder.resolve("x/b"), Progress()) }
    assertTrue("HTTP 404" in e.message!!)
    assertFalse(Files.exists(folder.resolve("x/b")))
  }

  @Test
  fun `only GET under the base path is served`() {
    val client = HttpClient.newHttpClient()
    fun status(uri: URI, method: String = "GET") = client.send(
      HttpRequest.newBuilder(uri).method(method, HttpRequest.BodyPublishers.noBody()).build(),
      BodyHandlers.discarding(),
    ).statusCode()
    assertEquals(200, status(base.resolve(HttpService.MANIFEST)))
    assertEquals(405, status(base.resolve(HttpService.MANIFEST), "POST"))
    assertEquals(405, status(base.resolve(HttpService.MANIFEST), "HEAD"))
    assertEquals(404, status(base.resolve("other")))
    assertEquals(404, status(base.resolve("files/")))
    assertEquals(404, status(URI("http://127.0.0.1:${service.port}/manifest.json")))
    assertEquals(404, status(URI("http://127.0.0.1:${service.port}/dont39-modsync/v1/files/../manifest.json")))
  }

  /** Answers one request with [CHANGED]'s headers and half its body, then [after]. */
  private fun cutServer(after: (ServerSocket) -> Unit): ServerSocket {
    val socket = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
    val body = Files.readAllBytes(serverItem.resolve(CHANGED))
    thread(isDaemon = true) {
      socket.accept().use { client ->
        val reader = client.getInputStream().bufferedReader()
        while (reader.readLine().isNotEmpty()) {
          // the request's headers
        }
        val out = client.getOutputStream()
        out.write("HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\n\r\n".toByteArray())
        out.write(body, 0, body.size / 2)
        out.flush()
        after(socket)
      }
    }
    return socket
  }

  @Test
  fun `a download cut off stops the join`() {
    cutServer {}.use { cut ->
      val file = item.files.first { it.path == CHANGED }
      val target = folder.resolve("cut/a.lua")
      assertThrows<IOException> {
        Fetcher(URI("http://127.0.0.1:${cut.localPort}/dont39-modsync/v1/")).download(file, target, Progress())
      }
      assertFalse(Files.exists(target))
    }
  }

  @Test
  fun `a stalled download is cancelled`() {
    val release = CountDownLatch(1)
    cutServer { release.await(WAIT_MILLIS, TimeUnit.MILLISECONDS) }.use { cut ->
      val file = item.files.first { it.path == CHANGED }
      val fetcher = Fetcher(URI("http://127.0.0.1:${cut.localPort}/dont39-modsync/v1/"))
      val worker = Worker("test download") { progress ->
        fetcher.download(file, folder.resolve("stall/a.lua"), progress)
      }.start()
      // until the half that was sent has been read
      val deadline = System.currentTimeMillis() + WAIT_MILLIS
      while (worker.progress.bytesDone == 0L && System.currentTimeMillis() < deadline) {
        Thread.sleep(10)
      }
      assertTrue(worker.progress.bytesDone > 0)
      assertEquals(null, worker.outcome)
      worker.cancel()
      assertEquals(Worker.Outcome.Cancelled, worker.await(WAIT_MILLIS))
      release.countDown()
    }
  }

  @Test
  fun `a worker reports what its body did`() {
    assertEquals(Worker.Outcome.Done(3), Worker("test") { 3 }.start().await(WAIT_MILLIS))
    val error = IllegalStateException("boom")
    assertEquals(Worker.Outcome.Failed(error), Worker<Int>("test") { throw error }.start().await(WAIT_MILLIS))
    val sleeping = Worker("test") { Thread.sleep(WAIT_MILLIS) }.start()
    sleeping.cancel()
    assertEquals(Worker.Outcome.Cancelled, sleeping.await(WAIT_MILLIS))
  }
}
