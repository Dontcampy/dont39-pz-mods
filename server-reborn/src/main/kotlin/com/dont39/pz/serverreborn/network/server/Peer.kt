package com.dont39.pz.serverreborn.network.server

import com.dont39.pz.serverreborn.network.protocol.Key
import com.dont39.pz.serverreborn.network.protocol.WireKey
import zombie.characters.IsoPlayer
import zombie.core.raknet.UdpConnection

/** One connection's sync state on the server. Main thread only. */
internal class Peer(val connection: UdpConnection, total: Counters) {
  /** A snapshot the client asked for; [opened]: after the client's OpenCheck, not a frame entry. */
  class Job(val key: Key, val wire: WireKey, val opened: Boolean)

  var active = false

  /** Why the server stopped answering this connection, or null. */
  var stopped: String? = null
  var modVersion: String? = null

  /** Every object this connection was sent or changed, with the frame that last covered it (R1). */
  val versions = HashMap<Key, Long>()

  /** This tick's objects, kept until the connection is fully connected. */
  val touched = LinkedHashMap<Key, Touches.Touch>()

  /** The last frame's number; snapshots carry it as their version. */
  var frame = 0L
  val snapshots = ArrayDeque<Job>()
  val queued = HashSet<Key>()

  val session = Counters(total)

  val name: String get() = connection.userName ?: "connection ${connection.index}"

  /** The connection's first player, or null. */
  val player: IsoPlayer? get() = connection.players.firstOrNull { it != null }

  /** Stops answering this connection: nothing more goes to it. */
  fun stop(why: String) {
    active = false
    stopped = why
    touched.clear()
    snapshots.clear()
    queued.clear()
  }
}
