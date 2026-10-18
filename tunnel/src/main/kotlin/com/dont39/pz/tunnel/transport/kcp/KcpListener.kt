package com.dont39.pz.tunnel.transport.kcp

import com.dont39.pz.tunnel.LocalChannels
import com.dont39.pz.tunnel.OpenListener
import com.dont39.pz.tunnel.TunnelIo
import com.dont39.pz.tunnel.TunnelLog
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * The server end: one UDP socket on [port] for every player's KCP tunnel, each relayed from its own local UDP sockets
 * to the game's RakNet ports, [gamePort] (channel 0) and [udpPort] (channel 1). Sessions are keyed by the player's
 * address and conv; datagrams for no open session are dropped and counted.
 */
internal class KcpListener(
  private val port: Int,
  private val gamePort: Int,
  private val udpPort: Int,
  private val params: KcpParams,
  private val log: TunnelLog,
) : OpenListener {
  private companion object {
    // all players share this socket
    const val UDP_BUFFER = 4 shl 20
    const val RETRY_MILLIS = 100L
  }

  private data class Key(val from: SocketAddress, val id: Int)

  private class Opened(val session: KcpSession, val welcome: ByteArray)

  private val socket = DatagramSocket(null as SocketAddress?)
  private val sessions = ConcurrentHashMap<Key, KcpSession>()

  // by address and nonce, so a repeated HELLO gets the same conv
  private val byHello = ConcurrentHashMap<Key, Opened>()
  private val scheduler = KcpScheduler("KCP listener on UDP port $port, ticks", KcpScheduler.period(params), log)
  private val dropped = AtomicLong()

  @Volatile
  private var closed = false

  override val details = params.toString()

  /** Throws when the port cannot be opened. */
  fun start() {
    try {
      socket.receiveBufferSize = UDP_BUFFER
      socket.sendBufferSize = UDP_BUFFER
      socket.bind(InetSocketAddress(port))
    } catch (e: IOException) {
      socket.close()
      throw e
    }
    scheduler.start()
    thread(isDaemon = true, name = "${TunnelIo.THREAD_PREFIX}KCP listener on UDP port $port") { receive() }
  }

  override fun close() {
    if (closed) {
      return
    }
    closed = true
    sessions.values.forEach { it.close("the server stopped its KCP listener") }
    scheduler.stop()
    socket.close()
  }

  private fun receive() {
    val buffer = ByteArray(TunnelIo.MAX_DATAGRAM)
    val packet = DatagramPacket(buffer, buffer.size)
    var failing = false
    try {
      while (!closed) {
        try {
          packet.setData(buffer, 0, buffer.size)
          socket.receive(packet)
          dispatch(packet.socketAddress, buffer, packet.length)
          failing = false
        } catch (e: IOException) {
          if (closed) {
            break
          }
          // a failure that repeats would flood the log
          if (!failing) {
            log.info { "could not receive or answer on KCP port $port: ${TunnelIo.describe(e)}" }
          }
          failing = true
          Thread.sleep(RETRY_MILLIS)
        }
      }
      log.info { "stopped listening on KCP port $port (dropped ${dropped.get()} datagrams for no open tunnel)" }
    } catch (t: Throwable) {
      log.error("receiving on KCP port $port", t)
      close()
    }
  }

  private fun dispatch(from: SocketAddress, buffer: ByteArray, length: Int) {
    if (length < KcpWire.CONV_SIZE) {
      dropped.incrementAndGet()
      return
    }
    val conv = KcpWire.conv(buffer, 0)
    if (conv == 0) {
      hello(from, buffer, length)
      return
    }
    val session = sessions[Key(from, conv)]
    if (session == null) {
      dropped.incrementAndGet()
      return
    }
    session.input(buffer, 0, length)
  }

  private fun hello(from: SocketAddress, buffer: ByteArray, length: Int) {
    // silent towards anything that is not a tunnel client
    val (_, nonce) = KcpWire.readHello(buffer, 0, length) ?: run {
      dropped.incrementAndGet()
      return
    }
    if (nonce == null) {
      answer(KcpWire.otherVersion(), from)
      return
    }
    val welcome = (byHello[Key(from, nonce)] ?: open(from, nonce) ?: return).welcome
    answer(welcome, from)
  }

  private fun answer(datagram: ByteArray, to: SocketAddress) = socket.send(DatagramPacket(datagram, datagram.size, to))

  private fun open(from: SocketAddress, nonce: Int): Opened? {
    val label = "KCP tunnel from ${TunnelIo.describe(from)}"
    val local = try {
      LocalChannels.toGame(gamePort, udpPort)
    } catch (e: IOException) {
      log.info { "could not open a $label: ${TunnelIo.describe(e)}" }
      return null
    }
    var conv: Int
    do {
      conv = Random.nextInt()
    } while (conv == 0 || sessions.containsKey(Key(from, conv)))
    val key = Key(from, conv)
    val helloKey = Key(from, nonce)
    val session = KcpSession(
      label,
      conv,
      params,
      local,
      { datagram, length -> socket.send(DatagramPacket(datagram, length, from)) },
      log,
    ) {
      sessions.remove(key)
      byHello.remove(helloKey)
      scheduler.remove(it)
    }
    val opened = Opened(session, KcpWire.welcome(nonce, conv, udpPort, params))
    sessions[key] = session
    byHello[helloKey] = opened
    // RakNet and the admin tools see the player at this address
    log.info { "$label open as 127.0.0.1:${local.localPort(TunnelIo.GAME_CHANNEL)}" }
    session.start()
    scheduler.add(session)
    return opened
  }
}
