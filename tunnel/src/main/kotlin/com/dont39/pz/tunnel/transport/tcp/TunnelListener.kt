package com.dont39.pz.tunnel.transport.tcp

import com.dont39.pz.tunnel.OpenListener
import com.dont39.pz.tunnel.TunnelIo
import com.dont39.pz.tunnel.TunnelLog
import java.io.IOException
import java.net.DatagramSocket
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
    runCatching { server.close() }
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
            log.info { "could not accept a tunnel connection on TCP port $port: ${TunnelIo.describe(e)}" }
          }
          failing = true
          Thread.sleep(ACCEPT_RETRY_MILLIS)
          continue
        }
        failing = false
        thread(isDaemon = true, name = "${TunnelIo.THREAD_PREFIX}connection from ${address(socket)}") { serve(socket) }
      }
      log.info { "stopped listening on TCP port $port" }
    } catch (t: Throwable) {
      log.error("accepting tunnel connections on TCP port $port", t)
      close()
    }
  }

  private fun serve(socket: Socket) {
    val from = address(socket)
    val opened = mutableListOf<DatagramSocket>()
    try {
      Wire.configure(socket)
      Wire.serverHello(socket, HELLO_MILLIS, udpPort)
      val targets = listOf(gamePort, udpPort).map { InetSocketAddress(TunnelIo.LOOPBACK, it) }
      targets.forEach { opened += TunnelIo.datagramSocket(0).apply { connect(it) } }
      // RakNet and the admin tools see the player at this address
      log.info { "tunnel from $from open as 127.0.0.1:${opened[TunnelIo.GAME_CHANNEL].localPort}" }
      Relay("tunnel from $from", socket, opened, targets, log).start()
    } catch (e: IOException) {
      closeAll(socket, opened)
      log.info { "could not open a tunnel for $from: ${TunnelIo.describe(e)}" }
    } catch (t: Throwable) {
      closeAll(socket, opened)
      log.error("opening a tunnel for $from", t)
    }
  }

  private fun closeAll(socket: Socket, opened: List<DatagramSocket>) {
    runCatching { socket.close() }
    opened.forEach { runCatching { it.close() } }
  }

  private fun address(socket: Socket) = "${socket.inetAddress.hostAddress}:${socket.port}"
}
