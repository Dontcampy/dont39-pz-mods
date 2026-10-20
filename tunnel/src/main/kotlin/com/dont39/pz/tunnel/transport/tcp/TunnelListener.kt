package com.dont39.pz.tunnel.transport.tcp

import com.dont39.pz.common.describe
import com.dont39.pz.tunnel.LocalChannels
import com.dont39.pz.tunnel.OpenListener
import com.dont39.pz.tunnel.TunnelIo
import com.dont39.pz.tunnel.TunnelLog
import com.dont39.pz.tunnel.closeQuietly
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * The server end: accepts tunnel clients on TCP [port] and relays each from its own local UDP sockets to the game's
 * RakNet ports, [gamePort] (channel 0) and [udpPort] (channel 1).
 */
internal class TunnelListener(
  private val port: Int,
  private val gamePort: Int,
  private val udpPort: Int,
  private val log: TunnelLog,
) : OpenListener {
  private companion object {
    const val HELLO_MILLIS = 5000
    const val ACCEPT_RETRY_MILLIS = 1000L
  }

  private val server = ServerSocket()

  /** Throws when the port cannot be opened. */
  fun start() {
    try {
      server.bind(InetSocketAddress(port))
    } catch (e: IOException) {
      server.close()
      throw e
    }
    thread(isDaemon = true, name = "${TunnelIo.THREAD_PREFIX}listener on TCP port $port") { accept() }
  }

  override fun close() {
    server.closeQuietly()
  }

  private fun accept() {
    var failing = false
    try {
      while (!server.isClosed) {
        val socket = try {
          server.accept()
        } catch (e: IOException) {
          if (server.isClosed) {
            break
          }
          // a client that resets before it is accepted must not end the listener; repeats would flood the log
          if (!failing) {
            log.info { "could not accept a tunnel connection on TCP port $port: ${describe(e)}" }
          }
          failing = true
          Thread.sleep(ACCEPT_RETRY_MILLIS)
          continue
        }
        failing = false
        val from = TunnelIo.describe(socket.remoteSocketAddress)
        thread(isDaemon = true, name = "${TunnelIo.THREAD_PREFIX}connection from $from") { serve(socket, from) }
      }
      log.info { "stopped listening on TCP port $port" }
    } catch (t: Throwable) {
      log.error("accepting tunnel connections on TCP port $port", t)
      close()
    }
  }

  private fun serve(socket: Socket, from: String) {
    var local: LocalChannels? = null
    try {
      Wire.configure(socket)
      Wire.serverHello(socket, HELLO_MILLIS, udpPort)
      val channels = LocalChannels.toGame(gamePort, udpPort).also { local = it }
      // RakNet and the admin tools see the player at this address
      log.info { "tunnel from $from open as 127.0.0.1:${channels.localPort(TunnelIo.GAME_CHANNEL)}" }
      Relay("tunnel from $from", socket, channels, log).start()
    } catch (e: IOException) {
      socket.closeQuietly()
      local?.close()
      log.info { "could not open a tunnel for $from: ${describe(e)}" }
    } catch (t: Throwable) {
      socket.closeQuietly()
      local?.close()
      log.error("opening a tunnel for $from", t)
    }
  }
}
