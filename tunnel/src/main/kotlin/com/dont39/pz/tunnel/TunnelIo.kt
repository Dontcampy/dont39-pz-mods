package com.dont39.pz.tunnel

import com.dont39.pz.tunnel.TunnelFailed.Reason
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.PortUnreachableException
import java.net.SocketTimeoutException

/** What every transport shares: the channels it carries and the local UDP sockets RakNet talks to. */
internal object TunnelIo {
  /** The server's game port (DefaultPort). */
  const val GAME_CHANNEL = 0

  /** The server's UDPPort, which a Steam-mode client connects RakNet to. */
  const val UDP_PORT_CHANNEL = 1
  const val CHANNELS = 2

  /** The largest UDP payload. */
  const val MAX_DATAGRAM = 65507

  const val THREAD_PREFIX = "Dont39AllInOne tunnel: "

  // map downloads arrive in bursts
  private const val UDP_BUFFER = 1 shl 20

  /** RakNet listens on IPv4; the JVM's loopback address may be IPv6. */
  val LOOPBACK: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

  /** On 127.0.0.1; port 0 for any. */
  fun datagramSocket(port: Int): DatagramSocket = DatagramSocket(InetSocketAddress(LOOPBACK, port)).apply {
    receiveBufferSize = UDP_BUFFER
    sendBufferSize = UDP_BUFFER
  }

  /**
   * The client's sockets on 127.0.0.1 where RakNet sends, one per port of [ports], for the [transport] tunnel at
   * [target]. Throws [TunnelFailed] when one is taken.
   */
  fun listenLocally(ports: List<Int>, transport: String, target: String): List<DatagramSocket> {
    val opened = mutableListOf<DatagramSocket>()
    for (port in ports) {
      try {
        opened += datagramSocket(port)
      } catch (e: IOException) {
        opened.forEach { runCatching { it.close() } }
        val why = describe(e)
        throw TunnelFailed(
          Reason.LOCAL_PORT,
          listOf(port.toString(), why),
          "could not listen on 127.0.0.1:$port for the $transport tunnel at $target ($why)",
          e,
        )
      }
    }
    return opened
  }

  /**
   * Throws [TunnelFailed] when [address], the server at [host], is this machine: the tunnel would bind the server's own
   * ports on 127.0.0.1 and take the server relay's datagrams, a loop.
   */
  fun checkNotThisMachine(address: InetAddress, host: String, transport: String, target: String) {
    val local = try {
      address.isLoopbackAddress || address.isAnyLocalAddress || NetworkInterface.getByInetAddress(address) != null
    } catch (e: IOException) {
      val why = describe(e)
      throw TunnelFailed(
        Reason.NETWORK,
        listOf(transport, target, why),
        "could not check whether $host is this machine ($why)",
        e,
      )
    }
    if (local) {
      throw TunnelFailed(
        Reason.THIS_MACHINE,
        listOf(host),
        "the server at $host runs on this machine, where the tunnel cannot listen on its ports",
      )
    }
  }

  /** Why probing the [transport] tunnel at [target] ("host:port") failed on [e]. */
  fun probeFailed(transport: String, target: String, e: IOException): TunnelFailed = when (e) {
    is SocketTimeoutException ->
      TunnelFailed(Reason.NO_ANSWER, listOf(transport, target), "no answer from a $transport tunnel at $target", e)
    is ConnectException, is PortUnreachableException -> TunnelFailed(
      Reason.REFUSED,
      listOf(transport, target),
      "no $transport tunnel at $target, the connection was refused (${describe(e)})",
      e,
    )
    is NotATunnel ->
      TunnelFailed(Reason.NOT_A_TUNNEL, listOf(transport, target), "$target is not a $transport tunnel of this mod", e)
    is OtherVersion -> TunnelFailed(
      Reason.VERSION,
      listOf(transport, target, e.theirs.toString(), e.ours.toString()),
      "the $transport tunnel at $target speaks protocol version ${e.theirs}, this mod version ${e.ours}",
      e,
    )
    else -> TunnelFailed(
      Reason.NETWORK,
      listOf(transport, target, describe(e)),
      "could not open the $transport tunnel at $target (${describe(e)})",
      e,
    )
  }

  fun describe(e: IOException): String = when (e) {
    is EOFException -> "closed by the other end"
    else -> e.message?.let { "${e.javaClass.simpleName}: $it" } ?: e.javaClass.simpleName
  }
}
