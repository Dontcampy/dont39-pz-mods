package com.dont39.pz.modsync.server

import com.dont39.pz.modsync.protocol.Protocol
import com.dont39.pz.modsync.protocol.Protocol.BASE_PATH
import com.dont39.pz.modsync.protocol.Protocol.FILES
import com.dont39.pz.modsync.protocol.Protocol.MANIFEST
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** The server's HTTP side: the [Protocol] layout, nothing else. */
internal class HttpService private constructor(private val server: HttpServer, private val pool: ExecutorService) :
  AutoCloseable {
  /** What the service reports; it runs on its own threads. */
  interface Events {
    /** A request failed on the network, e.g. the client went away: one line, nothing else is affected. */
    fun ioFailed(message: String)

    /** Anything else: an error of the feature. */
    fun failed(t: Throwable)
  }

  val port: Int
    get() = server.address.port

  override fun close() {
    server.stop(0)
    pool.shutdownNow()
  }

  companion object {
    private const val THREADS = 4

    /** [files]: the file of each hash [manifest] lists. */
    fun start(address: InetSocketAddress, manifest: ByteArray, files: Map<String, Path>, events: Events): HttpService {
      val pool = Executors.newFixedThreadPool(THREADS) { task ->
        Thread(task, "Dont39AllInOne mod-sync HTTP").apply { isDaemon = true }
      }
      val server = try {
        HttpServer.create(address, 0)
      } catch (e: IOException) {
        pool.shutdownNow()
        throw e
      }
      server.createContext(BASE_PATH) { exchange ->
        try {
          serve(exchange, manifest, files)
        } catch (e: IOException) {
          events.ioFailed("serving ${exchange.requestURI} to ${exchange.remoteAddress} failed: $e")
        } catch (t: Throwable) {
          events.failed(t)
        } finally {
          exchange.close()
        }
      }
      server.executor = pool
      try {
        startAsDaemon(server)
      } catch (t: Throwable) {
        server.stop(0)
        pool.shutdownNow()
        throw t
      }
      return HttpService(server, pool)
    }

    // the dispatcher thread inherits the daemon flag of the thread that starts it; from the game's main thread it
    // would keep a stopped server's JVM alive
    private fun startAsDaemon(server: HttpServer) {
      var failure: Throwable? = null
      val starter = Thread({
        try {
          server.start()
        } catch (t: Throwable) {
          failure = t
        }
      }, "Dont39AllInOne mod-sync HTTP start").apply { isDaemon = true }
      starter.start()
      starter.join()
      failure?.let { throw it }
    }

    private fun serve(exchange: HttpExchange, manifest: ByteArray, files: Map<String, Path>) {
      if (exchange.requestMethod != "GET") {
        exchange.responseHeaders.add("Allow", "GET")
        exchange.sendResponseHeaders(405, -1)
        return
      }
      val name = exchange.requestURI.rawPath.removePrefix(BASE_PATH)
      val file = if (name.startsWith(FILES)) files[name.removePrefix(FILES)] else null
      when {
        name == MANIFEST -> {
          exchange.responseHeaders.add("Content-Type", "application/json")
          exchange.sendResponseHeaders(200, manifest.size.toLong())
          exchange.responseBody.write(manifest)
        }
        file != null -> {
          exchange.responseHeaders.add("Content-Type", "application/octet-stream")
          exchange.sendResponseHeaders(200, Files.size(file))
          Files.newInputStream(file).use { it.transferTo(exchange.responseBody) }
        }
        else -> exchange.sendResponseHeaders(404, -1)
      }
    }
  }
}
