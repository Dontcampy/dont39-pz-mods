package com.dont39.pz.tunnel.transport.tcp

import com.dont39.pz.tunnel.OpenListener
import com.dont39.pz.tunnel.OpenTunnel
import com.dont39.pz.tunnel.Transport
import com.dont39.pz.tunnel.TunnelLog
import com.dont39.pz.tunnel.TunnelOptions

/** Datagrams framed on one TCP connection per player, for networks that drop UDP. */
internal object TcpTransport : Transport {
  // the probe runs on the game thread, which freezes the menu for at most both
  private const val CONNECT_MILLIS = 1000
  private const val HELLO_MILLIS = 1000

  override val name = "TCP"

  // released before the KCP tunnel, so not Dont39AllInOne_Tunnel.<Option>
  override val portOption = "Dont39AllInOne_TcpTunnel.Port"

  override fun open(host: String, port: Int, gamePort: Int, log: TunnelLog): OpenTunnel =
    ClientTunnel.open(host, port, gamePort, CONNECT_MILLIS, HELLO_MILLIS, log)

  override fun listen(port: Int, gamePort: Int, udpPort: Int, options: TunnelOptions, log: TunnelLog): OpenListener =
    TunnelListener(port, gamePort, udpPort, log).apply { start() }
}
