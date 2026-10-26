package com.dont39.pz.serverreborn.networksyncoptimization.server

import com.dont39.pz.serverreborn.ServerLoop
import com.dont39.pz.serverreborn.networksyncoptimization.game.Containers
import com.dont39.pz.serverreborn.networksyncoptimization.game.ItemDigests
import com.dont39.pz.serverreborn.networksyncoptimization.game.Net
import com.dont39.pz.serverreborn.networksyncoptimization.protocol.Codec
import com.dont39.pz.serverreborn.networksyncoptimization.protocol.ItemKey
import com.dont39.pz.serverreborn.networksyncoptimization.protocol.Key
import com.dont39.pz.serverreborn.networksyncoptimization.protocol.Message.SyncFrame
import com.dont39.pz.serverreborn.networksyncoptimization.protocol.Protocol
import com.dont39.pz.serverreborn.networksyncoptimization.protocol.WireKey

/**
 * Sync frames (R4): each connection's touched objects with their digests, at most [PER_SECOND] times per second
 * whatever the tick rate, so the packets, the snapshot budget and the frame-counted state do not grow with it. Main
 * thread only.
 */
internal object Frames {
  const val PER_SECOND = 10

  // a frame packet's entries, well within the connection's 1,000,000-byte send buffer
  private const val FRAME_BYTES = 256 * 1024

  // each touched object's entry, computed once per frame for all connections
  private val digests = HashMap<Key, SyncFrame.Entry>()
  private var ticks = 0

  /** At each tick's end: whether this tick sends the frames, every tick at 10 per second, every other one at 20. */
  fun due(): Boolean {
    val every = (ServerLoop.ticksPerSecond + PER_SECOND - 1) / PER_SECOND
    if (++ticks < every) return false
    ticks = 0
    return true
  }

  /** Drops the entries computed for this frame. */
  fun clear() = digests.clear()

  /** Sends [p] its touched objects, if any. */
  fun send(p: Peer) {
    if (p.touched.isEmpty()) return
    val version = ++p.frame
    var gone = 0
    var changes = 0
    val entries = p.touched.values.map { t ->
      var e = digests.getOrPut(t.key) { resolve(t) }
      if (t.changed) {
        e = e.asChanged()
        changes++
      }
      if (e.gone) {
        gone++
        p.versions.remove(t.key)
      } else {
        p.versions[t.key] = version
      }
      e
    }
    p.touched.clear()
    var bytes = 0
    var from = 0
    var size = 0
    entries.forEachIndexed { i, e ->
      size += Codec.size(e)
      if (size > FRAME_BYTES) {
        bytes += send(p, version, entries.subList(from, i))
        from = i
        size = Codec.size(e)
      }
    }
    bytes += send(p, version, entries.subList(from, entries.size))
    p.session.frame(entries.size, gone, changes, bytes)
  }

  private fun send(p: Peer, version: Long, entries: List<SyncFrame.Entry>): Int {
    val frame = SyncFrame(version, entries)
    return Net.send(p.connection, Protocol.SHARED_CHANNEL) { Codec.encode(frame, it) }
  }

  private fun resolve(t: Touches.Touch): SyncFrame.Entry {
    val key = t.key
    val wire = if (key is ItemKey) WireKey.item(t.wire, key.itemId) else WireKey.container(t.wire)
    if (!Containers.live(t.container, key.container)) {
      return SyncFrame.Entry(wire, true, 0)
    }
    if (key is ItemKey) {
      val item = t.container.getItemWithID(key.itemId) ?: return SyncFrame.Entry(wire, true, 0)
      return SyncFrame.Entry(wire, false, ItemDigests.item(item))
    }
    return SyncFrame.Entry(wire, false, ItemDigests.container(t.container))
  }
}
