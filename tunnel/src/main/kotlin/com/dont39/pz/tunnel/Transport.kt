package com.dont39.pz.tunnel

import java.io.IOException

/** One way to carry the game's datagrams between a client and the server, both ends. */
internal interface Transport {
  /** In log lines, e.g. "TCP". */
  val name: String

  /**
   * Probes [host]:[port] and opens a tunnel within the transport's bound, listening locally on [gamePort] and the
   * server's UDPPort. Throws [TunnelFailed].
   */
  fun open(host: String, port: Int, gamePort: Int, log: TunnelLog): OpenTunnel

  /**
   * Accepts tunnels on [port], relaying each to the game's [gamePort] and [udpPort]. Throws when the port cannot be
   * opened.
   */
  fun listen(port: Int, gamePort: Int, udpPort: Int, log: TunnelLog): OpenListener
}

/** The client's tunnel to the server. */
internal interface OpenTunnel {
  fun close(reason: String)
}

internal interface OpenListener {
  fun close()
}

/** Why a tunnel could not be opened. */
internal class TunnelFailed(message: String, cause: IOException? = null) : IOException(message, cause)

/** Where the tunnel reports: the feature's log in game. */
internal interface TunnelLog {
  fun info(message: () -> String)

  /** Not a network failure. */
  fun error(what: String, t: Throwable)
}
