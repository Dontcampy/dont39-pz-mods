package com.dont39.pz.serverreborn.network.server

import com.dont39.pz.serverreborn.network.game.Containers
import com.dont39.pz.serverreborn.network.game.ItemDigests
import com.dont39.pz.serverreborn.network.protocol.ItemKey
import com.dont39.pz.serverreborn.network.protocol.Key
import com.dont39.pz.serverreborn.network.protocol.Message
import com.dont39.pz.serverreborn.network.protocol.WireKey

/** A client's snapshot requests and open checks (R6, R7), queued for [Snapshots]. Main thread only. */
internal object Requests {
  // per connection; more are refused, the client asks again at a later mismatch
  private const val MAX_QUEUED = 1024

  fun snapshots(p: Peer, r: Message.SnapshotRequest) {
    r.keys.forEach { wire ->
      val key = key(p, wire)
      val refused = key == null || key !in p.versions || p.queued.size >= MAX_QUEUED
      p.session.requested(refused)
      if (!refused && p.queued.add(key)) {
        p.snapshots.add(Peer.Job(key, wire, false))
      }
    }
  }

  fun openCheck(p: Peer, o: Message.OpenCheck) {
    val at = parse(p, o.key.containerBytes)
    val key = if (at == null) null else key(o.key)
    if (at == null || key == null || !Relevance.relevant(p.connection, key)) {
      p.session.requested(true)
      return
    }
    val container = at.container
    var digest: Long? = null
    if (container != null && Containers.live(container, key.container)) {
      digest = if (key is ItemKey) {
        container.getItemWithID(key.itemId)?.let(ItemDigests::item)
      } else {
        ItemDigests.container(container)
      }
    }
    val differed = digest == null || digest != o.digest
    p.session.check(differed)
    p.versions.putIfAbsent(key, p.frame)
    if (differed && p.queued.size < MAX_QUEUED && p.queued.add(key)) {
      p.snapshots.add(Peer.Job(key, o.key, true))
    }
  }

  /**
   * The container a client's bytes address, or null when there is none to look in: the client's garbage, a parent that
   * left (the game's lookup throws for a player who is gone), a nested bag that is gone.
   */
  fun parse(p: Peer, wire: ByteArray): Containers.Resolved? = try {
    Containers.find(wire, p.connection)
  } catch (_: RuntimeException) {
    null
  }

  private fun key(p: Peer, wire: WireKey): Key? = if (parse(p, wire.containerBytes) == null) null else key(wire)

  /** Null for a floor container, and for an address this version of the mod cannot read. */
  private fun key(wire: WireKey): Key? {
    val ck = try {
      Containers.keyOf(wire.containerBytes)
    } catch (_: IllegalStateException) {
      return null
    } ?: return null
    return if (wire.isItem) ItemKey(ck, wire.itemId) else ck
  }
}
