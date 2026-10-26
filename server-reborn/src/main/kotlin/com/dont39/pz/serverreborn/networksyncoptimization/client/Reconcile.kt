package com.dont39.pz.serverreborn.networksyncoptimization.client

import com.dont39.pz.serverreborn.networksyncoptimization.game.ItemDigests
import com.dont39.pz.serverreborn.networksyncoptimization.game.PinnedContainerID
import com.dont39.pz.serverreborn.networksyncoptimization.protocol.ContainerDigest
import com.dont39.pz.serverreborn.networksyncoptimization.protocol.ContainerKey
import com.dont39.pz.serverreborn.networksyncoptimization.protocol.Message.Snapshot
import com.dont39.pz.serverreborn.networksyncoptimization.protocol.WireKey
import zombie.Lua.LuaEventManager
import zombie.characters.IsoPlayer
import zombie.core.network.ByteBufferReader
import zombie.core.raknet.UdpConnection
import zombie.inventory.InventoryItem
import zombie.inventory.ItemContainer
import zombie.network.packets.AddInventoryItemToContainerPacket
import zombie.network.packets.INetworkPacket
import zombie.network.packets.ItemStatsPacket
import zombie.network.packets.SyncHandWeaponFieldsPacket
import zombie.network.packets.SyncItemFieldsPacket
import zombie.network.packets.SyncItemModDataPacket
import zombie.vehicles.VehiclePart
import java.nio.ByteBuffer

/**
 * Applies a snapshot in place (R6, D5), by item id and with the game's own packet classes, so fields mean what the
 * game's updates mean: extras off the client, missing items added from the snapshot's item list, then each item's
 * field payloads in the order the server wrote them. Nothing here sends to the server. Main thread only.
 */
internal object Reconcile {
  enum class Outcome {
    /** Something changed and the digest now matches, apart from items in both whose fields still differ. */
    REPAIRED,

    /** Nothing needed to change: the client caught up since it asked (stuck items aside, as for REPAIRED). */
    MATCHED,

    /** Items or their types still differ, or the game failed on the snapshot. */
    UNREPAIRABLE,

    /** The client no longer has the object under that key. */
    NOT_FOUND,
  }

  /** An item the snapshot left differing: its digest on the client after it, and the server's. */
  class Stuck(val id: Int, val mine: Long, val server: Long)

  /**
   * [failure]: why it is unrepairable; [cause]: the game's exception behind it, if any. [stuck]: items that still
   * differ while everything else now matches.
   */
  class Result(
    val outcome: Outcome,
    val name: String,
    val added: List<Int> = emptyList(),
    val removed: List<Int> = emptyList(),
    val changed: List<Int> = emptyList(),
    val stuck: List<Stuck> = emptyList(),
    val failure: String? = null,
    val cause: Throwable? = null,
  ) {
    val changedAnything get() = added.isNotEmpty() || removed.isNotEmpty() || changed.isNotEmpty()
  }

  /** The game failed on this snapshot, or its state does not take it: only this key fails, the session goes on. */
  private class Failed(message: String, cause: Throwable?) : RuntimeException(message, cause)

  /** [diff]: null unless the field diff is on. Throws only for what this version of the mod gets wrong. */
  fun apply(s: Snapshot, connection: UdpConnection?, diff: FieldDiff?): Result {
    val wire = s.key
    val found = Lookup.find(wire, connection)
    val name = found.name(wire)
    val container = found.container ?: return Result(Outcome.NOT_FOUND, name)
    val serverIds = s.items.map { it.id }.toSet()
    check(!wire.isItem || serverIds == setOf(wire.itemId)) { "the snapshot of $name holds items $serverIds" }
    val mine = covered(container, wire)
    val before = digests(mine)
    val beforeFields = diff?.let { FieldDiff.of(mine.values) }
    var serverFields: Map<Int, FieldDiff.Fields>? = null
    // no game id addresses a nested bag: its payloads are read against the bag found here
    val pin = container.takeIf { found.key?.kind == ContainerKey.Kind.NESTED }
    try {
      val list = itemList(s, name, container, pin, connection)
      if (diff != null) {
        serverFields = FieldDiff.of(list.items.filterNotNull())
      }
      if (!wire.isItem) {
        remove(container, found.part, mine.values.filter { it.getID() !in serverIds })
      }
      add(list, serverIds - mine.keys, connection)
      s.items.forEach { item ->
        listOf(
          Triple(SyncItemFieldsPacket(), item.fields, "SyncItemFields"),
          Triple(SyncItemModDataPacket(), item.modData, "SyncItemModData"),
          Triple(ItemStatsPacket(), item.stats, "ItemStats"),
          Triple(SyncHandWeaponFieldsPacket(), item.weapon, "SyncHandWeaponFields"),
        ).forEach { (packet, bytes, type) ->
          payload(PinnedContainerID.pinned(packet, pin), bytes, type, item.id, connection)
        }
      }
    } catch (f: Failed) {
      return Result(Outcome.UNREPAIRABLE, name, failure = f.message, cause = f.cause)
    }
    val after = covered(container, wire)
    val added = after.keys.filter { it !in mine }
    val removed = mine.keys.filter { it !in after }
    val now = digests(after)
    val changed = now.filter { (id, digest) -> before[id]?.let { it != digest } == true }.keys.toList()
    val server = s.items.associate { it.id to it.digest }
    if (diff != null) {
      diff.applied(wire, name, beforeFields!!, FieldDiff.of(after.values), serverFields!!, server)
    }
    // items in both whose fields still differ; everything else must match, with those taken as the server has them
    val stuck = now.mapNotNull { (id, digest) -> server[id]?.takeIf { it != digest }?.let { Stuck(id, digest, it) } }
    val matches = if (wire.isItem) {
      after.size == 1 && wire.itemId in server
    } else {
      val d = ContainerDigest()
      after.forEach { (id, item) -> d.add(id, item.fullType, server[id] ?: now.getValue(id)) }
      d.get() == s.digest
    }
    if (!matches) {
      return Result(
        Outcome.UNREPAIRABLE,
        name,
        added,
        removed,
        changed,
        failure = "its digest still differs from the server's after the snapshot",
      )
    }
    val repaired = Result(Outcome.REPAIRED, name, added, removed, changed, stuck)
    return if (repaired.changedAnything) repaired else Result(Outcome.MATCHED, name, added, removed, changed, stuck)
  }

  /** The client's items the snapshot covers: all of a container's, or the one item. */
  private fun covered(container: ItemContainer, wire: WireKey): Map<Int, InventoryItem> =
    if (wire.isItem) {
      listOfNotNull(container.getItemWithID(wire.itemId))
    } else {
      container.getItems().toList()
    }.associateBy { it.getID() }

  private fun digests(items: Map<Int, InventoryItem>): Map<Int, Long> = items.mapValues { ItemDigests.item(it.value) }

  // the server's items as AddInventoryItemToContainer carries them, read but not added yet
  private fun itemList(
    s: Snapshot,
    name: String,
    container: ItemContainer,
    pin: ItemContainer?,
    connection: UdpConnection?,
  ): AddInventoryItemToContainerPacket {
    check(s.add.isNotEmpty()) { "the snapshot of $name has no item list" }
    val list = PinnedContainerID.pinned(AddInventoryItemToContainerPacket(), pin)
    game("reading the snapshot's item list") { list.parse(reader(s.add), connection) }
    if (list.containerId.getContainer() !== container) {
      throw Failed("its item list names another container on this client", null)
    }
    return list
  }

  /**
   * Takes items off the client as the game's own updates do, without a request: worn ones first as
   * SyncClothingPacket.processClient does, then the container's removal, which also empties the hands (as
   * RemoveInventoryItemFromContainerPacket.processClient). Attached items are left to the hotbar's own update.
   */
  private fun remove(container: ItemContainer, part: VehiclePart?, extras: List<InventoryItem>) {
    if (extras.isEmpty()) return
    val player = container.character as? IsoPlayer
    if (player != null && player.isLocalPlayer) {
      val worn = extras.filter(player::isEquippedClothing)
      if (worn.isNotEmpty()) {
        game("taking off worn items") {
          worn.forEach { player.wornItems.remove(it) }
          player.onWornItemsChanged()
          player.resetModelNextFrame()
          LuaEventManager.triggerEvent("OnClothingUpdated", player)
        }
      }
    }
    game("removing items") {
      extras.forEach { container.removeItemWithID(it.getID()) }
      container.isExplored = true
      part?.setContainerContentAmount(container.capacityWeight)
    }
  }

  private fun add(list: AddInventoryItemToContainerPacket, missing: Set<Int>, connection: UdpConnection?) {
    // the game would print a "dupe" line for each item already there
    list.items.removeIf { it == null || it.getID() !in missing }
    if (list.items.isEmpty()) return
    if (!game("checking the items to add") { list.isConsistent(connection) }) {
      throw Failed("the game found the items to add inconsistent", null)
    }
    game("adding items") { list.processClient(connection) }
  }

  // as the game handles its own: one it finds inconsistent is skipped, and the digest check reports what is left
  private fun payload(packet: INetworkPacket, bytes: ByteArray, type: String, itemId: Int, connection: UdpConnection?) {
    if (bytes.isEmpty()) return
    game("applying $type to item $itemId") {
      packet.parse(reader(bytes), connection)
      if (packet.isConsistent(connection)) {
        packet.processClient(connection)
      }
    }
  }

  private fun reader(bytes: ByteArray) = ByteBufferReader(ByteBuffer.wrap(bytes))

  private inline fun <T> game(what: String, call: () -> T): T = try {
    call()
  } catch (e: RuntimeException) {
    throw Failed("the game failed $what", e)
  }
}
