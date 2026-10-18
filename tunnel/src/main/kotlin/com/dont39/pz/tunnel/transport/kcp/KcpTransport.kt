package com.dont39.pz.tunnel.transport.kcp

import com.dont39.pz.tunnel.OpenListener
import com.dont39.pz.tunnel.OpenTunnel
import com.dont39.pz.tunnel.Transport
import com.dont39.pz.tunnel.TunnelLog
import com.dont39.pz.tunnel.TunnelOptions

/**
 * KCP with FEC over UDP, one conversation per player, for networks that lose packets: recovers faster than TCP, at the
 * cost of bandwidth.
 */
internal object KcpTransport : Transport {
  // the probe runs on the game thread, which freezes the menu for at most this
  private const val HELLO_MILLIS = 2000
  private const val HELLO_EVERY_MILLIS = 250

  override val name = "KCP"

  override val portOption = KcpParams.OPTION_PREFIX + "KcpPort"

  override fun open(host: String, port: Int, gamePort: Int, log: TunnelLog): OpenTunnel =
    KcpClient.open(host, port, gamePort, HELLO_EVERY_MILLIS, HELLO_MILLIS, log)

  override fun listen(port: Int, gamePort: Int, udpPort: Int, options: TunnelOptions, log: TunnelLog): OpenListener =
    KcpListener(port, gamePort, udpPort, KcpParams.read(options), log).apply { start() }
}
