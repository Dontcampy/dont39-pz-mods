package com.dont39.pz.serverreborn.network.server

import com.dont39.pz.serverreborn.ServerReborn
import com.dont39.pz.serverreborn.network.game.Containers
import com.dont39.pz.serverreborn.network.game.ItemDigests
import com.dont39.pz.serverreborn.network.game.Net
import com.dont39.pz.serverreborn.network.game.PinnedContainerID
import com.dont39.pz.serverreborn.network.game.Settings
import com.dont39.pz.serverreborn.network.protocol.ContainerDigest
import com.dont39.pz.serverreborn.network.protocol.ContainerKey
import com.dont39.pz.serverreborn.network.protocol.ItemKey
import com.dont39.pz.serverreborn.network.protocol.Message.Snapshot
import com.dont39.pz.serverreborn.network.protocol.Payload
import com.dont39.pz.serverreborn.network.protocol.Protocol
import com.dont39.pz.serverreborn.network.protocol.SnapshotWriter
import zombie.characters.IsoPlayer
import zombie.core.network.ByteBufferWriter
import zombie.inventory.InventoryItem
import zombie.inventory.ItemContainer
import zombie.inventory.types.HandWeapon
import zombie.network.packets.AddInventoryItemToContainerPacket
import zombie.network.packets.ItemStatsPacket
import zombie.network.packets.SyncHandWeaponFieldsPacket
import zombie.network.packets.SyncItemFieldsPacket
import zombie.network.packets.SyncItemModDataPacket
import java.nio.BufferOverflowException
import java.nio.ByteBuffer

/**
 * Snapshots (R6): an object's state written with the game's own packet classes, so the client applies it with the same
 * code as the game's updates. Main thread only.
 */
internal object Snapshots {
  // the connection's send buffer is 1,000,000 bytes, the packet header 3 of them
  private val SCRATCH = ByteBuffer.allocate(999_000)

  /** Longest value list logged per item; clothing visuals alone have over a hundred values. */
  private const val LOGGED_CHARS = 4000

  // per connection and frame, so at most 80 snapshots and 640 KiB per second
  private const val SNAPSHOTS_PER_FRAME = 8
  private const val SNAPSHOT_BYTES_PER_FRAME = 64 * 1024

  /** At a frame, sends queued snapshots up to the frame's budget; a single one may exceed the bytes. */
  fun serve(p: Peer) {
    if (p.snapshots.isEmpty()) return
    val player = p.player ?: return
    var sent = 0
    var bytes = 0
    while (p.snapshots.isNotEmpty() && sent < SNAPSHOTS_PER_FRAME && bytes < SNAPSHOT_BYTES_PER_FRAME) {
      val job = p.snapshots.removeFirst()
      p.queued.remove(job.key)
      // read once already when asked; null when the parent left since
      val at = Requests.parse(p, job.wire.containerBytes)
      bytes += send(p, job, at?.container, player)
      sent++
    }
  }

  private fun send(p: Peer, job: Peer.Job, container: ItemContainer?, requester: IsoPlayer): Int {
    val version = p.frame
    val out = SCRATCH
    out.clear()
    var tooLarge = false
    val state = try {
      Touches.ownWrite = true
      write(out, job, container, version, requester)
    } catch (_: BufferOverflowException) {
      tooLarge = true
      out.clear()
      SnapshotWriter.start(out, job.wire, version, Snapshot.State.TOO_LARGE, 0).add(null).finish()
      Snapshot.State.TOO_LARGE
    } finally {
      Touches.ownWrite = false
    }
    if (state == Snapshot.State.PRESENT) {
      p.versions[job.key] = version
    } else {
      p.versions.remove(job.key)
    }
    if (tooLarge) {
      ServerReborn.warn { "${p.name}: ${job.key} does not fit into one packet; it is not repaired" }
    } else {
      // the server's half of a repair's log (R9); the client logs what the snapshot changed
      val where = if (state == Snapshot.State.PRESENT) "present" else "no longer there"
      val why = if (job.opened) {
        "the client's own check of it differed"
      } else {
        "it differed from a sync frame entry on the client"
      }
      ServerReborn.log { "${p.name}: sent the server's state of ${job.key} ($where), as $why" }
    }
    out.flip()
    val bytes = Net.send(p.connection, Protocol.SHARED_CHANNEL) { it.put(out) }
    p.session.snapshot(tooLarge, bytes)
    return bytes
  }

  private fun write(
    out: ByteBuffer,
    job: Peer.Job,
    container: ItemContainer?,
    version: Long,
    requester: IsoPlayer,
  ): Snapshot.State {
    var live = Containers.live(container, job.key.container)
    var only: InventoryItem? = null
    val key = job.key
    if (live && key is ItemKey) {
      only = container!!.getItemWithID(key.itemId)
      live = only != null
    }
    if (!live || container == null) {
      SnapshotWriter.start(out, job.wire, version, Snapshot.State.GONE, 0).add(null).finish()
      return Snapshot.State.GONE
    }
    val items = if (only != null) listOf(only) else ItemDigests.items(container)
    val digests = items.map(ItemDigests::item)
    val whole = ContainerDigest()
    items.forEachIndexed { i, item -> whole.add(item.getID(), item.fullType, digests[i]) }
    val digest = if (only != null) digests[0] else whole.get()
    // the game's field packets name a player; the owner's, or the asking player for a world container
    val player = container.character as? IsoPlayer ?: requester
    // no game id addresses a nested bag; the client pins the payloads to the bag it finds from the snapshot's key
    val pin = container.takeIf { key.container.kind == ContainerKey.Kind.NESTED }
    val w = SnapshotWriter.start(out, job.wire, version, Snapshot.State.PRESENT, digest)
    w.add { b ->
      val add = PinnedContainerID.pinned(AddInventoryItemToContainerPacket(), pin)
      add.setData(container, ArrayList(items))
      add.write(ByteBufferWriter(b))
    }
    if (Settings.fieldDiff) {
      logValues(job, items)
    }
    items.forEachIndexed { i, item ->
      w.item(
        item.getID(),
        digests[i],
        fields(player, item, pin),
        if (item.hasModData()) modData(item, pin) else null,
        stats(container, item, pin),
        (item as? HandWeapon)?.let { weapon(player, it, pin) },
      )
    }
    w.finish()
    return Snapshot.State.PRESENT
  }

  // the client's field diff compares with the item as the snapshot's save restores it; this is the live item
  private fun logValues(job: Peer.Job, items: List<InventoryItem>) {
    items.forEach { item ->
      val values = ItemDigests.fields(item).values.entries
        // unset clothing visuals
        .filterNot { (name, value) -> name.startsWith("visual.") && value == "0.0" }
        .joinToString(", ") { (name, value) -> "$name=$value" }
      val shown = if (values.length > LOGGED_CHARS) values.substring(0, LOGGED_CHARS) + "..." else values
      ServerReborn.log { "field values: ${job.key} item ${item.getID()} (${item.fullType}): $shown" }
    }
  }

  private fun fields(player: IsoPlayer, item: InventoryItem, pin: ItemContainer?): Payload = { b ->
    val packet = PinnedContainerID.pinned(SyncItemFieldsPacket(), pin)
    packet.setData(player, item)
    packet.write(ByteBufferWriter(b))
  }

  private fun modData(item: InventoryItem, pin: ItemContainer?): Payload = { b ->
    val packet = PinnedContainerID.pinned(SyncItemModDataPacket(), pin)
    packet.setData(item)
    packet.write(ByteBufferWriter(b))
  }

  private fun stats(container: ItemContainer, item: InventoryItem, pin: ItemContainer?): Payload = { b ->
    val packet = PinnedContainerID.pinned(ItemStatsPacket(), pin)
    packet.setData(container, item)
    packet.write(ByteBufferWriter(b))
  }

  private fun weapon(player: IsoPlayer, weapon: HandWeapon, pin: ItemContainer?): Payload = { b ->
    val packet = PinnedContainerID.pinned(SyncHandWeaponFieldsPacket(), pin)
    packet.setData(player, weapon)
    packet.write(ByteBufferWriter(b))
  }
}
