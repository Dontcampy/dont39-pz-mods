package com.dont39.pz.serverreborn.network.server

import com.dont39.pz.serverreborn.ServerLoop
import com.dont39.pz.serverreborn.ServerReborn
import com.dont39.pz.serverreborn.network.game.Containers
import com.dont39.pz.serverreborn.network.game.PacketScope
import com.dont39.pz.serverreborn.network.game.Settings
import com.dont39.pz.serverreborn.network.protocol.ItemKey
import com.dont39.pz.serverreborn.network.protocol.Key
import zombie.core.network.ByteBufferReader
import zombie.core.raknet.UdpConnection
import zombie.inventory.ItemContainer
import zombie.network.IConnection
import zombie.network.PacketTypes.PacketType
import zombie.network.fields.ContainerID
import zombie.network.packets.AddInventoryItemToContainerPacket
import zombie.network.packets.INetworkPacket
import zombie.network.packets.ItemStatsPacket
import zombie.network.packets.RemoveInventoryItemFromContainerPacket
import zombie.network.packets.ReplaceInventoryItemInContainerPacket
import zombie.network.packets.SyncHandWeaponFieldsPacket
import zombie.network.packets.SyncItemFieldsPacket
import zombie.network.packets.SyncItemModDataPacket
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Touch recording (R3, D7): which connection was sent or sent which in-scope object, and which containers' contents
 * changed, until the next frame covers them. Main thread, except [contentChanged] and the sends of other threads.
 */
internal object Touches {
  /**
   * One object touched since the last frame, as the first packet or change that touched it addressed it. [changed]:
   * its contents changed, so the client gives a mod's own sync of it time to land.
   */
  class Touch(val key: Key, val container: ItemContainer, val wire: ByteArray, val changed: Boolean) {
    fun asChanged() = if (changed) this else Touch(key, container, wire, true)
  }

  // containers whose items changed on the main thread since the last tick's end (D7)
  private val changed: MutableSet<ItemContainer> = Collections.newSetFromMap(IdentityHashMap())

  // debug only: item changes of containers off the main thread since the last summary, which are not recorded
  private val offThreadChanges = AtomicLong()

  // debug only: packets the game sent since the last summary, by type
  private val sentTypes = HashMap<String, Int>()

  // the in-scope packet the game wrote last, paired with the send that follows it
  private var written: INetworkPacket? = null

  /** The mod's own writes with the game's packet classes are not sends. */
  var ownWrite = false

  /** Drops what was recorded, after a stop; main thread. */
  fun clear() {
    changed.clear()
    sentTypes.clear()
    written = null
  }

  /** At the RETURN of an in-scope packet class's write. */
  fun written(packet: INetworkPacket) {
    if (!ServerSync.isRunning || ownWrite) return
    if (ServerLoop.onMainThread) {
      written = packet
    } else if (Settings.touches) {
      debug { "write of ${packet.javaClass.simpleName} on thread ${Thread.currentThread().name}, not the main thread" }
    }
  }

  /** At PacketType.send HEAD: the packet written last goes to [connection]. Every packet passes here. */
  fun sending(type: PacketType, connection: IConnection?) {
    if (!ServerSync.isRunning) return
    val onMain = ServerLoop.onMainThread
    if (Settings.touches && onMain) {
      sentTypes.merge(type.name, 1, Int::plus)
    }
    if (!onMain) {
      // another thread's send must not take the main thread's packet; an in-scope one is an error
      if (PacketScope.kind(type) != null && connection is UdpConnection) {
        ServerSync.activePeer(connection)
      }
      return
    }
    val packet = written
    if (packet == null) {
      if (Settings.touches && PacketScope.kind(type) != null) {
        debug { "send of ${type.name} with no write seen before it" }
      }
      return
    }
    written = null
    if (PacketScope.kind(type) != null && connection is UdpConnection) {
      val p = ServerSync.activePeer(connection)
      if (p != null) {
        touch(p, packet)
      } else if (Settings.touches) {
        debug { "send of ${type.name} to ${connection.connectedGUID}, not an active connection" }
      }
    }
  }

  /** At PacketType.onServerPacket HEAD: where the packet's data starts, or -1 when it is not of interest. */
  fun receiving(type: PacketType, input: ByteBufferReader?): Int =
    if (ServerSync.isRunning && PacketScope.kind(type) != null) input!!.bb.position() else -1

  /**
   * At PacketType.onServerPacket FINALLY: the sender touched the object whether the game applied, rejected or failed on
   * it. A packet the game read is the connection's cached one; an unauthorized one it did not read.
   */
  fun received(type: PacketType, input: ByteBufferReader?, connection: UdpConnection?, start: Int) {
    if (start < 0) return
    val p = ServerSync.activePeer(connection) ?: return
    if (input!!.bb.position() != start) {
      touch(p, p.connection.getPacket(type))
      return
    }
    val kind = PacketScope.kind(type)
    // these start with the container; the item packets need only a login, which a peer has
    if (kind == PacketScope.Kind.REMOVE || kind == PacketScope.Kind.DELETE) {
      val data = input.bb.duplicate()
      data.position(start)
      val id = ContainerID()
      try {
        id.parse(ByteBufferReader(data), connection)
      } catch (_: RuntimeException) {
        // the client's bytes, which the game would not have read either
        return
      }
      touchContainer(p, id)
    }
  }

  private fun touch(p: Peer, packet: INetworkPacket?) {
    when (packet) {
      is AddInventoryItemToContainerPacket -> touchContainer(p, packet.containerId)
      is RemoveInventoryItemFromContainerPacket -> touchContainer(p, packet.containerId)
      is ReplaceInventoryItemInContainerPacket -> touchContainer(p, packet.containerId)
      is SyncItemFieldsPacket -> touchItem(p, packet.containerId, packet.itemId)
      is SyncItemModDataPacket -> touchItem(p, packet.containerId, packet.itemId)
      is SyncHandWeaponFieldsPacket -> touchItem(p, packet.containerId, packet.itemId)
      is ItemStatsPacket -> touchItem(p, packet.containerId, packet.id)
      else -> throw IllegalStateException("an in-scope packet type sent a ${packet?.javaClass?.name}")
    }
  }

  private fun touchContainer(p: Peer, id: ContainerID) {
    val container = id.getContainer()
    val at = container?.let { Containers.of(id) }
    if (Settings.touches) {
      debug { "touch ${id.getContainerType()} ${at?.key}: ${outcome(container, at)}" }
    }
    if (container != null && at != null) {
      p.touched.getOrPut(at.key) { Touch(at.key, container, at.wire, false) }
    }
  }

  private fun touchItem(p: Peer, id: ContainerID, itemId: Int) {
    val container = id.getContainer()
    val at = container?.let { Containers.of(id) }
    if (Settings.touches) {
      debug { "touch item $itemId in ${id.getContainerType()} ${at?.key}: ${outcome(container, at)}" }
    }
    if (container != null && at != null) {
      val key = ItemKey(at.key, itemId)
      p.touched.getOrPut(key) { Touch(key, container, at.wire, false) }
    }
  }

  private fun outcome(container: ItemContainer?, at: Containers.Address?) = when {
    container == null -> "no container"
    at == null -> "not addressed"
    else -> "recorded"
  }

  /**
   * At the HEAD of each ItemContainer method through which items enter or leave a container (D7), on either side and
   * any thread. Only the server's main thread counts: chunk loading builds containers on its own threads, and those
   * reach clients with the chunk.
   */
  fun contentChanged(container: ItemContainer) {
    if (!ServerSync.isRunning || !ServerSync.anyActive) return
    if (ServerLoop.onMainThread) {
      changed.add(container)
    } else if (Settings.touches) {
      offThreadChanges.incrementAndGet()
    }
  }

  /**
   * At each tick's end: each container changed this tick, as one touch for every checked connection the game sends
   * that container's changes to; a send of the same key is marked changed too, as the change may also travel another
   * way.
   */
  fun recordChanges(checked: List<Peer>) {
    if (changed.isEmpty()) return
    changed.forEach { container ->
      val at = Containers.address(container)
      var to = 0
      if (at != null) {
        checked.filter { Relevance.relevant(it.connection, at.key) }.forEach { p ->
          p.touched.merge(at.key, Touch(at.key, container, at.wire, true)) { sent, _ -> sent.asChanged() }
          to++
        }
      }
      if (Settings.touches) {
        debug {
          "content change of ${at?.key ?: "a container the game does not address"}: recorded for $to connections"
        }
      }
    }
    changed.clear()
  }

  /** Logs the debug counts since the last summary, and starts them again. */
  fun summary() {
    if (sentTypes.isNotEmpty()) {
      debug { "sent since the last summary: $sentTypes" }
      sentTypes.clear()
    }
    val offThread = offThreadChanges.getAndSet(0)
    if (offThread > 0) {
      debug { "$offThread item changes of containers off the main thread since the last summary, not recorded" }
    }
  }

  private inline fun debug(message: () -> String) = ServerReborn.log { "debug: ${message()}" }
}
