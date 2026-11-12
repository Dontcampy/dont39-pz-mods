package com.dont39.pz.serverreborn.network.game

import com.dont39.pz.serverreborn.network.protocol.ContainerKey
import com.dont39.pz.serverreborn.network.protocol.ContainerWire
import com.dont39.pz.serverreborn.network.protocol.ProtocolException
import zombie.characters.IsoPlayer
import zombie.core.network.ByteBufferReader
import zombie.inventory.ItemContainer
import zombie.inventory.types.InventoryContainer
import zombie.network.GameClient
import zombie.network.GameServer
import zombie.network.IConnection
import zombie.network.fields.ContainerID
import zombie.vehicles.BaseVehicle
import zombie.vehicles.VehicleManager
import zombie.vehicles.VehiclePart
import java.nio.ByteBuffer

/**
 * Containers as the game addresses them on the wire (`ContainerID`), the mod's nested address of bags the game cannot
 * address, and the mod's keys for them.
 */
internal object Containers {
  // a ContainerID without floor squares is a few dozen bytes
  private const val MAX_WIRE = 256

  private val PLAYER_KINDS = setOf(ContainerKey.Kind.PLAYER, ContainerKey.Kind.BAG)

  /** A container's key and the mod's bytes for it. */
  class Address(val key: ContainerKey, val wire: ByteArray)

  /** A container found from the mod's bytes, and the vehicle part whose items it holds, if any. */
  class Resolved(val container: ItemContainer?, val part: VehiclePart?)

  /** Throws when the game's container types are not the ones [ContainerWire] reads. */
  fun check() {
    val types = ContainerID.ContainerType.entries.map { it.name }
    check(types == ContainerWire.TYPES) {
      "the game's container types are $types, this version of the mod reads ${ContainerWire.TYPES}"
    }
  }

  /**
   * The key of the container [id] addresses, or null for a floor container (the game builds a throwaway one from the
   * square's items on each side, and square object lists are out of scope) and for one the game leaves undefined (the
   * loot window's own floor container off any square).
   */
  fun keyOf(id: ContainerID): ContainerKey? {
    val type = checkNotNull(id.getContainerType()) { "a ContainerID without a type" }
    // a floor id lists squares, which wire() has no room for
    return if (type == ContainerID.ContainerType.Floor || type == ContainerID.ContainerType.Undefined) {
      null
    } else {
      keyOf(wire(id))
    }
  }

  /** The key of the mod's bytes [wire]; null for a floor container. */
  fun keyOf(wire: ByteArray): ContainerKey? = try {
    ContainerWire.decode(wire)
  } catch (e: ProtocolException) {
    throw IllegalStateException("a container id this version of the mod cannot read: ${e.message}", e)
  }

  /**
   * The game's bytes of [id]. A player's containers have the mod's form only in an id the server set or either side
   * parsed: a client's `set` writes the local player index.
   */
  fun wire(id: ContainerID): ByteArray {
    val b = ByteBuffer.allocate(MAX_WIRE)
    id.write(b)
    return b.array().copyOf(b.position())
  }

  /**
   * The game's address of [container] as it is now, or null when the game cannot address it any more: taken out of the
   * world, or its parent object off its square.
   */
  fun locate(container: ItemContainer): ContainerID? {
    val id = ContainerID()
    try {
      id.set(container)
    } catch (_: RuntimeException) {
      // "Unable to resolve container location" for no place; a parent object without a square fails inside set
      return null
    }
    return id
  }

  /**
   * The mod's address of [container] as it sits now, without a lookup: the game's own, a player's container by the
   * player's online id, or a nested one for a bag the game's ids cannot address. Null for a floor container, one out
   * of the world, a container of a player without an online id, a bag of a player without a square, and a bag whose
   * outer container has no address or that is more than [ContainerKey.MAX_BAGS] bags deep.
   */
  fun current(container: ItemContainer): Address? {
    if (unaddressedBag(container)) return nested(container)
    // a player's containers in the order ContainerID.set decides them
    val inventoryOf = container.getParent() as? IsoPlayer
    if (inventoryOf != null) return owned(inventoryOf, null)
    val bag = container.getContainingItem()
    val bagOf = bag?.outermostContainer?.getParent() as? IsoPlayer
    if (bag != null && bagOf != null) return owned(bagOf, bag.getID())
    val id = locate(container) ?: return null
    val key = keyOf(id)
    check(key?.kind !in PLAYER_KINDS) { "the game addresses $key, where the mod sees no player's container" }
    return address(id, key)
  }

  // not through ContainerID.set: on a client it writes the local player index, and an error for another player
  private fun owned(player: IsoPlayer, bagId: Int?): Address? {
    val id = player.getOnlineID().toInt()
    return when {
      id < 0 -> null
      bagId == null -> Address(ContainerKey.player(id), ContainerWire.playerInventory(id))
      // a snapshot's game packets cannot name the bag yet: their ContainerID.set reads this field
      player.square == null -> null
      else -> Address(ContainerKey.bag(id, bagId), ContainerWire.playerBag(id, bagId))
    }
  }

  /** The address of the container a game id was set to: the id's own, or the nested one of a bag it cannot name. */
  fun of(id: ContainerID): Address? {
    val container = id.getContainer()
    return if (container != null && unaddressedBag(container)) nested(container) else address(id, keyOf(id))
  }

  private fun address(id: ContainerID, key: ContainerKey?) = key?.let { Address(it, wire(id)) }

  /**
   * Where a client finds [container], or null when the lookup of its address would not end at this very container: a
   * floor container, one not in the world, an object no longer on its square, a bag whose outer container has no
   * address.
   */
  fun address(container: ItemContainer): Address? {
    val at = current(container) ?: return null
    // indexOf of an object no longer on its square; the game's lookup would log an error for it
    if (at.key.kind.square && at.key.index < 0) return null
    return at.takeIf { find(at.wire, null)?.container === container }
  }

  /**
   * Whether [container] is still the one addressed as [key]: not taken out of the world, not at another object index
   * or in another bag, not a stand-in the game's lookup made up for an index out of range.
   */
  fun live(container: ItemContainer?, key: ContainerKey): Boolean =
    container != null && current(container)?.key == key

  /**
   * Finds the container the mod's bytes address: a player's by the online id, on either side; the others by the game's
   * own lookup, then the bags from the anchor for a nested address. Null when the game's lookup throws, and for a
   * player, bag, vehicle part or nested bag that is gone. Throws when the bytes are not one whole address.
   */
  fun find(wire: ByteArray, connection: IConnection?): Resolved? {
    val key = keyOf(wire) ?: return inWorld(wire, connection)
    return when (key.kind) {
      ContainerKey.Kind.NESTED -> inBags(key, ContainerWire.anchor(wire), connection)
      ContainerKey.Kind.PLAYER, ContainerKey.Kind.BAG -> ofPlayer(key)
      else -> if (parentThere(key)) inWorld(wire, connection) else null
    }
  }

  private fun inWorld(wire: ByteArray, connection: IConnection?): Resolved? =
    lookUp(wire, connection)?.let { Resolved(it.getContainer(), it.getPart()) }

  // not through ContainerID.parse: a server reads the id as a player index on the connection
  private fun ofPlayer(key: ContainerKey): Resolved? {
    val players = if (GameServer.server) GameServer.IDToPlayerMap else GameClient.IDToPlayerMap
    val inventory = players[key.owner.toShort()]?.getInventory() ?: return null
    if (key.kind == ContainerKey.Kind.PLAYER) return Resolved(inventory, null)
    val bag = inventory.getItemWithIDRecursiv(key.sub) as? InventoryContainer
    return bag?.itemContainer?.let { Resolved(it, null) }
  }

  private fun inBags(key: ContainerKey, anchor: ByteArray, connection: IConnection?): Resolved? {
    var at: ItemContainer = find(anchor, connection)?.container ?: return null
    // not a stand-in the game's lookup made up for an index out of range
    if (!live(at, key.anchor!!)) return null
    for (bagId in key.bags) {
      val bag = at.getItemWithID(bagId) as? InventoryContainer ?: return null
      at = bag.itemContainer ?: return null
    }
    return Resolved(at, null)
  }

  /**
   * Whether the game's ids cannot address [container]: a bag in a world, corpse or floor-bag container at any depth,
   * which ContainerID.set names as the outer container, and a bag below the first level of a vehicle part, which
   * ContainerID.findObject does not search. Bags in a player's inventory and on the floor they address.
   */
  private fun unaddressedBag(container: ItemContainer): Boolean {
    // getters, not the public fields of the same names that property syntax would read
    val bag = container.getContainingItem()
    if (bag == null || bag.getWorldItem() != null) return false
    // null: in a floor container, which the game rebuilds from the squares on each side
    val outer = bag.outermostContainer ?: return false
    val root = outer.getParent()
    return root !is IsoPlayer && !(root is BaseVehicle && bag.container === outer)
  }

  // the bags from the first container the game addresses itself (a bag on the floor counts) down to this one
  private fun nested(container: ItemContainer): Address? {
    val bags = ArrayDeque<Int>()
    var at = container
    while (true) {
      val bag = at.getContainingItem()
      if (bag == null || bag.getWorldItem() != null) break
      if (bags.size == ContainerKey.MAX_BAGS) return null
      bags.addFirst(bag.getID())
      at = bag.container ?: return null
    }
    val id = locate(at) ?: return null
    val anchor = keyOf(id)
    if (anchor == null || !anchor.kind.anchors || anchor.kind.square && anchor.index < 0) return null
    val list = bags.toList()
    return Address(ContainerKey.nested(anchor, list), ContainerWire.nested(wire(id), list))
  }

  // the game's lookup logs an error for each of these that is gone, and a frame entry may name one that just left
  private fun parentThere(key: ContainerKey): Boolean = when (key.kind) {
    ContainerKey.Kind.VEHICLE, ContainerKey.Kind.VEHICLE_BAG -> {
      val items = VehicleManager.instance.getVehicleByID(key.owner.toShort())?.getPartByIndex(key.index)?.itemContainer
      items != null && (key.kind == ContainerKey.Kind.VEHICLE || items.getItemWithID(key.sub) is InventoryContainer)
    }
    else -> true
  }

  private fun lookUp(wire: ByteArray, connection: IConnection?): ContainerID? {
    val id = ContainerID()
    val input = ByteBufferReader(ByteBuffer.wrap(wire))
    try {
      id.parse(input, connection)
    } catch (_: RuntimeException) {
      return null
    }
    check(input.remaining() == 0) { "${input.remaining()} bytes after a container id" }
    return id
  }
}
