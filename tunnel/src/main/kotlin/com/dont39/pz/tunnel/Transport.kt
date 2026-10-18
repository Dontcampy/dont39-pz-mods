package com.dont39.pz.tunnel

import java.io.IOException
import java.net.ProtocolException

/** One way to carry the game's datagrams between a client and the server, both ends. */
internal interface Transport {
  /** In log lines, e.g. "TCP". */
  val name: String

  /** The server's option that sets the port; 0 turns this transport's listener off. */
  val portOption: String

  /**
   * Probes [host]:[port] and opens a tunnel within the transport's bound, listening locally on [gamePort] and the
   * server's UDPPort. Throws [TunnelFailed].
   */
  fun open(host: String, port: Int, gamePort: Int, log: TunnelLog): OpenTunnel

  /**
   * Accepts tunnels on [port], relaying each to the game's [gamePort] and [udpPort], with the rest of its settings
   * from [options]. Throws an [IOException] when the port cannot be opened.
   */
  fun listen(port: Int, gamePort: Int, udpPort: Int, options: TunnelOptions, log: TunnelLog): OpenListener
}

/** The client's tunnel to the server. */
internal interface OpenTunnel {
  /** For the join's log line, e.g. the settings the server sent; empty when there is nothing to add. */
  val details: String get() = ""

  /** Counters since the tunnel opened; null when the transport cannot see the link's loss (TCP resends in the OS). */
  fun stats(): LinkStats? = null

  fun close(reason: String)
}

/** Cumulative link counters of a tunnel, for the network status display, which takes rates from their deltas. */
internal data class LinkStats(
  val segmentsSent: Long,
  val retransmitsTimeout: Long,
  val retransmitsFast: Long,
  /** Datagrams the other end has sent: FEC sequence numbers, data and parity alike, also with FEC off. */
  val seqExpected: Long,
  /** Of [seqExpected], those that never arrived: their ratio is the inbound datagram loss. */
  val seqMissing: Long,
  val fecRecovered: Long,
  val fecUnrecoverable: Long,
  /** KCP's smoothed round-trip time; 0 before the first acknowledgement. */
  val srttMillis: Int,
)

internal interface OpenListener {
  /** For the listener's log line; empty when there is nothing to add. */
  val details: String get() = ""

  fun close()
}

/** The server owner's settings by name: the sandbox options in game. Throws for an option that is not there. */
internal interface TunnelOptions {
  fun int(name: String): Int

  fun boolean(name: String): Boolean
}

/**
 * Why a tunnel could not be opened: [message] for the log, [reason] with [args] for the join screen
 * (`UI_Dont39AllInOne_Tunnel_Failed_<key>`, arguments `%1`, `%2`, ...).
 */
internal class TunnelFailed(
  val reason: Reason,
  val args: List<String>,
  message: String,
  cause: IOException? = null,
) : IOException(message, cause) {
  enum class Reason(val key: String) {
    NO_ANSWER("NoAnswer"),
    REFUSED("Refused"),
    NOT_A_TUNNEL("NotTunnel"),
    VERSION("Version"),
    THIS_MACHINE("ThisMachine"),
    LOCAL_PORT("LocalPort"),
    NETWORK("Network"),
  }
}

/** The peer does not speak the tunnel protocol. */
internal class NotATunnel(message: String) : ProtocolException(message)

/** The peer speaks another version of the tunnel protocol. */
internal class OtherVersion(message: String, val theirs: Int, val ours: Int) : ProtocolException(message)

/** Where the tunnel reports: the feature's log in game. */
internal interface TunnelLog {
  fun info(message: () -> String)

  /** Not a network failure. */
  fun error(what: String, t: Throwable)
}
