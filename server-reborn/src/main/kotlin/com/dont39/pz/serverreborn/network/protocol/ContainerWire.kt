package com.dont39.pz.serverreborn.network.protocol

import java.nio.BufferUnderflowException
import java.nio.ByteBuffer

/**
 * The mod's key of a container id as the game's `ContainerID.write` writes it: read from the bytes, as the fields that
 * hold it are package-private. Also the mod's own nested address of a bag the game's ids cannot address:
 * `[byte NESTED][byte bag count][int bag id from the outermost one in, per bag][the anchor's container id]`.
 */
internal object ContainerWire {
  /** `ContainerID.ContainerType`'s constants in ordinal order, as this version of the mod reads them. */
  val TYPES = listOf(
    "Undefined", "DeadBody", "WorldObject", "IsoObject", "ObjectContainer", "ObjectInVehicle", "Vehicle",
    "PlayerInventory", "InventoryContainer", "Floor",
  )

  private const val UNDEFINED = 0
  private const val DEAD_BODY = 1
  private const val WORLD_OBJECT = 2
  private const val ISO_OBJECT = 3
  private const val OBJECT_CONTAINER = 4
  private const val OBJECT_IN_VEHICLE = 5
  private const val VEHICLE = 6
  private const val PLAYER_INVENTORY = 7
  private const val INVENTORY_CONTAINER = 8
  private const val FLOOR = 9

  // the mod's own, outside the game's types
  const val NESTED = 0x80

  /**
   * The key of the container [wire] addresses, or null for a floor container: the game builds a throwaway one from the
   * square's items on each side. Throws [ProtocolException] for bytes that are not one whole container id of a type
   * the game addresses, or one whole nested address.
   */
  fun decode(wire: ByteArray): ContainerKey? {
    val input = ByteBuffer.wrap(wire)
    return underflowIsProtocol {
      val key = read(input, outermost = true)
      if (input.hasRemaining()) {
        throw ProtocolException("${input.remaining()} bytes after a container id")
      }
      key
    }
  }

  /**
   * The nested address of [bags] (from the one in the anchor down) in the container whose game bytes are [anchor].
   * Throws [IllegalArgumentException] for an anchor no nested address starts at, and for no bags or more than
   * [ContainerKey.MAX_BAGS].
   */
  fun nested(anchor: ByteArray, bags: List<Int>): ByteArray {
    val key = try {
      decode(anchor)
    } catch (e: ProtocolException) {
      throw IllegalArgumentException("an anchor that is not a container id: ${e.message}", e)
    }
    require(key != null && key.kind.anchors) { "a nested address cannot start at ${key ?: "a floor container"}" }
    require(bags.size in 1..ContainerKey.MAX_BAGS) { "a nested address of ${bags.size} bags" }
    val out = ByteBuffer.allocate(2 + bags.size * Int.SIZE_BYTES + anchor.size)
    out.put(NESTED.toByte()).put(bags.size.toByte())
    bags.forEach { out.putInt(it) }
    return out.put(anchor).array()
  }

  /** The anchor's game bytes of a nested address. Throws [ProtocolException] for bytes that are not one. */
  fun anchor(nested: ByteArray): ByteArray {
    val key = decode(nested)
    if (key == null || key.kind != ContainerKey.Kind.NESTED) {
      throw ProtocolException("not a nested address")
    }
    return nested.copyOfRange(2 + key.bags.size * Int.SIZE_BYTES, nested.size)
  }

  /**
   * The id of the inventory that holds the bag [bagWire] addresses, so the owner is found without the game's bag
   * lookup. Throws [ProtocolException] for bytes that are not a bag in a player inventory.
   */
  fun ownerInventory(bagWire: ByteArray): ByteArray {
    val input = ByteBuffer.wrap(bagWire)
    return underflowIsProtocol {
      if (input.get().toInt() and 0xFF != INVENTORY_CONTAINER) {
        throw ProtocolException("not a bag in a player inventory")
      }
      ByteBuffer.allocate(1 + Short.SIZE_BYTES + 1)
        .put(PLAYER_INVENTORY.toByte())
        .putShort(input.getShort())
        .put(input.get())
        .array()
    }
  }

  private inline fun <T> underflowIsProtocol(read: () -> T): T = try {
    read()
  } catch (e: BufferUnderflowException) {
    throw ProtocolException("a container id ends early", e)
  }

  private fun read(input: ByteBuffer, outermost: Boolean): ContainerKey? {
    when (val type = input.get().toInt() and 0xFF) {
      NESTED -> {
        if (!outermost) {
          throw ProtocolException("a nested address inside another")
        }
        val count = input.get().toInt()
        if (count !in 1..ContainerKey.MAX_BAGS) {
          throw ProtocolException("a nested address of $count bags")
        }
        val bags = List(count) { input.getInt() }
        val anchor = read(input, outermost = false)
        if (anchor == null || !anchor.kind.anchors) {
          throw ProtocolException("a nested address starting at ${anchor ?: "a floor container"}")
        }
        return ContainerKey.nested(anchor, bags)
      }
      PLAYER_INVENTORY -> return ContainerKey.player(playerId(input))
      INVENTORY_CONTAINER -> {
        val player = playerId(input)
        return ContainerKey.bag(player, input.getInt())
      }
      UNDEFINED -> throw ProtocolException("a container id of type Undefined")
      !in TYPES.indices ->
        throw ProtocolException("a container id of type $type, which this version of the mod does not know")
      else -> return square(type, input)
    }
  }

  private fun square(type: Int, input: ByteBuffer): ContainerKey? {
    val x = input.getInt()
    val y = input.getInt()
    val z = input.get().toInt()
    return when (type) {
      DEAD_BODY -> ContainerKey.corpse(x, y, z, input.getShort().toInt())
      WORLD_OBJECT -> ContainerKey.worldItem(x, y, z, input.getInt())
      ISO_OBJECT -> ContainerKey.isoObject(x, y, z, input.getShort().toInt())
      OBJECT_CONTAINER -> {
        val index = input.getShort().toInt()
        ContainerKey.objectContainer(x, y, z, index, input.getShort().toInt())
      }
      // a vehicle's square is where it was when written; the key names it by id only
      VEHICLE -> {
        val vehicle = input.getShort().toInt()
        ContainerKey.vehicle(vehicle, input.getShort().toInt())
      }
      OBJECT_IN_VEHICLE -> {
        val vehicle = input.getShort().toInt()
        val part = input.getShort().toInt()
        ContainerKey.vehicleBag(vehicle, part, input.getInt())
      }
      FLOOR -> {
        // (x, y) pairs; the game reads none for a count of 0 or less
        val squares = input.get().toInt()
        if (squares > 0) {
          input.get(ByteArray(squares * 2 * Int.SIZE_BYTES))
        }
        null
      }
      else -> error("type $type")
    }
  }

  // PlayerID: the online id, then the player index the key leaves out
  private fun playerId(input: ByteBuffer): Int {
    val id = input.getShort().toInt()
    input.get()
    return id
  }
}
