package com.dont39.pz.serverreborn.network.protocol

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.assertThrows
import java.nio.ByteBuffer

/** Fixtures as ContainerID.write lays them out in the game build the mod was written against. */
class ContainerWireTest {
  private fun wire(build: ByteBuffer.() -> Unit): ByteArray {
    val b = ByteBuffer.allocate(256).apply(build)
    return b.array().copyOf(b.position())
  }

  private fun ByteBuffer.byte(v: Int) = put(v.toByte())

  private fun ByteBuffer.short(v: Int) = putShort(v.toShort())

  private fun ByteBuffer.square(type: Int, x: Int, y: Int, z: Int) = byte(type).putInt(x).putInt(y).byte(z)

  // PlayerID: online id, player index
  private fun inventory(player: Int, index: Int = 0) = wire { byte(PLAYER_INVENTORY).short(player).byte(index) }

  private fun nestedRaw(count: Int, bags: List<Int>, anchor: ByteArray) = wire {
    byte(ContainerWire.NESTED).byte(count)
    bags.forEach { putInt(it) }
    put(anchor)
  }

  private val crate = wire { square(OBJECT_CONTAINER, 10500, 9600, 0).short(4).short(1) }
  private val crateKey = ContainerKey.objectContainer(10500, 9600, 0, 4, 1)
  private val corpse = wire { square(DEAD_BODY, 10500, 9600, 1).short(2) }
  private val bagOnFloor = wire { square(WORLD_OBJECT, 10500, 9600, 0).putInt(5) }
  private val part = wire { square(VEHICLE, 0, 0, 0).short(77).short(5) }
  private val isoObject = wire { square(ISO_OBJECT, 1, 2, 0).short(3) }
  private val playerBag = wire { byte(INVENTORY_CONTAINER).short(12).byte(0).putInt(9) }
  private val vehicleBag = wire { square(OBJECT_IN_VEHICLE, 0, 0, 0).short(77).short(5).putInt(9) }
  private val floor = wire { square(FLOOR, 10500, 9600, 0).byte(0) }
  private val most = List(ContainerKey.MAX_BAGS) { 7 }
  private val tooMany = List(ContainerKey.MAX_BAGS + 1) { 7 }

  @Test
  fun `types are in the game's order`() {
    assertEquals(
      listOf(
        "Undefined", "DeadBody", "WorldObject", "IsoObject", "ObjectContainer", "ObjectInVehicle", "Vehicle",
        "PlayerInventory", "InventoryContainer", "Floor",
      ),
      ContainerWire.TYPES,
    )
    assertEquals("DeadBody", ContainerWire.TYPES[DEAD_BODY])
    assertEquals("Floor", ContainerWire.TYPES[FLOOR])
  }

  @TestFactory
  fun `addresses decode to their keys`() = listOf(
    Triple("player inventory", inventory(12, 1), ContainerKey.player(12)),
    Triple("bag in a player inventory", wire { byte(INVENTORY_CONTAINER).short(12).byte(0).putInt(987654) },
      ContainerKey.bag(12, 987654)),
    Triple("corpse", wire { square(DEAD_BODY, 10500, 9600, 1).short(3) }, ContainerKey.corpse(10500, 9600, 1, 3)),
    Triple("bag on the floor", wire { square(WORLD_OBJECT, 10500, 9600, 0).putInt(-42) },
      ContainerKey.worldItem(10500, 9600, 0, -42)),
    Triple("object's main container", wire { square(ISO_OBJECT, 10500, 9600, -1).short(4) },
      ContainerKey.isoObject(10500, 9600, -1, 4)),
    Triple("one of an object's containers", wire { square(OBJECT_CONTAINER, 10500, 9600, 2).short(4).short(1) },
      ContainerKey.objectContainer(10500, 9600, 2, 4, 1)),
    // the key ignores the square the vehicle was on
    Triple("vehicle part here", wire { square(VEHICLE, 10500, 9600, 0).short(77).short(5) },
      ContainerKey.vehicle(77, 5)),
    Triple("vehicle part there", wire { square(VEHICLE, 11000, 9000, 0).short(77).short(5) },
      ContainerKey.vehicle(77, 5)),
    Triple("bag in a vehicle part", wire { square(OBJECT_IN_VEHICLE, 10500, 9600, 0).short(77).short(5).putInt(123) },
      ContainerKey.vehicleBag(77, 5, 123)),
    Triple("shorts are signed as the game's fields", wire { square(VEHICLE, 0, 0, 0).short(-2).short(0x8000) },
      ContainerKey.vehicle(-2, Short.MIN_VALUE.toInt())),
    Triple("floor without squares", floor, null),
    Triple("floor with squares", wire {
      square(FLOOR, 10500, 9600, 0).byte(2).putInt(10500).putInt(9600).putInt(10501).putInt(9600)
    }, null),
    Triple("nested one bag deep", ContainerWire.nested(crate, listOf(-77)), ContainerKey.nested(crateKey, listOf(-77))),
    Triple("nested three bags deep", ContainerWire.nested(corpse, listOf(1, 2, 3)),
      ContainerKey.nested(ContainerKey.corpse(10500, 9600, 1, 2), listOf(1, 2, 3))),
    Triple("nested in a bag on the floor", ContainerWire.nested(bagOnFloor, listOf(6)),
      ContainerKey.nested(ContainerKey.worldItem(10500, 9600, 0, 5), listOf(6))),
    Triple("nested in a vehicle part", ContainerWire.nested(part, listOf(6, 7)),
      ContainerKey.nested(ContainerKey.vehicle(77, 5), listOf(6, 7))),
    Triple("nested in an object's main container", ContainerWire.nested(isoObject, listOf(6)),
      ContainerKey.nested(ContainerKey.isoObject(1, 2, 0, 3), listOf(6))),
    Triple("nested as deep as allowed", ContainerWire.nested(crate, most), ContainerKey.nested(crateKey, most)),
  ).map { (name, bytes, key) -> DynamicTest.dynamicTest(name) { assertEquals(key, ContainerWire.decode(bytes)) } }

  @Test
  fun `a nested address is its bags before its anchor's game bytes`() {
    val one = ContainerWire.nested(crate, listOf(-77))
    assertArrayEquals(nestedRaw(1, listOf(-77), crate), one)
    val key = ContainerWire.decode(one)!!
    assertEquals(ContainerKey.Kind.NESTED, key.kind)
    assertEquals(crateKey, key.anchor)
    assertArrayEquals(crate, ContainerWire.anchor(one))
    assertEquals("bag -77 in container 1 of object 4 at 10500,9600,0", key.toString())

    val three = ContainerWire.nested(corpse, listOf(1, 2, 3))
    assertEquals(listOf(1, 2, 3), ContainerWire.decode(three)!!.bags)
    assertArrayEquals(corpse, ContainerWire.anchor(three))
    assertEquals("bag 3 in bag 2 in bag 1 in corpse 2 at 10500,9600,1", ContainerWire.decode(three).toString())
  }

  @Test
  fun `nested keys compare by chain`() {
    fun key(vararg bags: Int) = ContainerWire.decode(ContainerWire.nested(crate, bags.toList()))
    assertEquals(key(1, 2), key(1, 2))
    assertNotEquals(key(1, 2), key(2, 1))
    assertNotEquals(key(2), key(1, 2))
  }

  @Test
  fun `container and item keys are values`() {
    assertEquals(ContainerKey.player(5), ContainerKey.player(5))
    assertEquals(ContainerKey.player(5).hashCode(), ContainerKey.player(5).hashCode())
    assertNotEquals(ContainerKey.player(5), ContainerKey.bag(5, 0))
    assertNotEquals(ContainerKey.isoObject(1, 2, 0, 3), ContainerKey.objectContainer(1, 2, 0, 3, 0))
    assertEquals(ItemKey(ContainerKey.vehicle(4, 2), 9), ItemKey(ContainerKey.vehicle(4, 2), 9))
  }

  @Test
  fun `the owner inventory of a bag`() {
    val bag = wire { byte(INVENTORY_CONTAINER).short(12).byte(1).putInt(987654) }
    assertArrayEquals(inventory(12, 1), ContainerWire.ownerInventory(bag))
    assertThrows<ProtocolException> { ContainerWire.ownerInventory(inventory(12, 1)) }
    assertThrows<ProtocolException> { ContainerWire.ownerInventory(byteArrayOf(INVENTORY_CONTAINER.toByte(), 0)) }
  }

  /** Bytes that are not one whole container id or nested address. */
  @TestFactory
  fun `refused addresses`(): List<DynamicTest> {
    val plain = wire { square(OBJECT_CONTAINER, 1, 2, 0).short(4).short(1) }
    val nested = ContainerWire.nested(crate, listOf(1, 2, 3))
    val truncations = listOf("plain" to plain, "nested" to nested).flatMap { (kind, whole) ->
      whole.indices.map { length -> "$kind cut at $length" to whole.copyOf(length) }
    }
    val badAnchors = listOf(
      "inventory" to inventory(12), "player bag" to playerBag, "vehicle bag" to vehicleBag, "floor" to floor,
      "nested address" to ContainerWire.nested(crate, listOf(1)),
    ).map { (name, anchor) -> "nested in a $name" to nestedRaw(1, listOf(1), anchor) }
    val badCounts = listOf(0, ContainerKey.MAX_BAGS + 1, -1).map { count ->
      "nested count $count" to nestedRaw(count, List(maxOf(count, 0)) { 7 }, crate)
    }
    return listOf(
      "floor with missing squares" to wire { square(FLOOR, 10500, 9600, 0).byte(2).putInt(10500).putInt(9600) },
      "undefined" to byteArrayOf(0),
      "an unknown type" to wire { square(10, 0, 0, 0) },
      "trailing bytes" to wire { square(ISO_OBJECT, 1, 2, 0).short(4).byte(0) },
      "nested with trailing bytes" to nested.copyOf(nested.size + 1),
    ).plus(truncations).plus(badAnchors).plus(badCounts).map { (name, bytes) ->
      DynamicTest.dynamicTest(name) { assertThrows<ProtocolException> { ContainerWire.decode(bytes) } }
    }.plus(
      DynamicTest.dynamicTest("the anchor of an address that is not nested") {
        assertThrows<ProtocolException> { ContainerWire.anchor(crate) }
      },
    )
  }

  /** Nested addresses the mod must never build. */
  @TestFactory
  fun `refused nested addresses`() = listOf(
    "in an inventory" to { ContainerWire.nested(inventory(12), listOf(1)) },
    "in a player bag" to { ContainerWire.nested(playerBag, listOf(1)) },
    "in a vehicle bag" to { ContainerWire.nested(vehicleBag, listOf(1)) },
    "in a floor container" to { ContainerWire.nested(floor, listOf(1)) },
    "in a nested address" to { ContainerWire.nested(ContainerWire.nested(crate, listOf(1)), listOf(1)) },
    "too many bags" to { ContainerWire.nested(crate, tooMany) },
    "no bags" to { ContainerWire.nested(crate, emptyList()) },
    "key in a player inventory" to { ContainerKey.nested(ContainerKey.player(1), listOf(1)) },
    "key with too many bags" to { ContainerKey.nested(crateKey, tooMany) },
  ).map { (name, build) -> DynamicTest.dynamicTest(name) { assertThrows<IllegalArgumentException> { build() } } }

  private companion object {
    const val DEAD_BODY = 1
    const val WORLD_OBJECT = 2
    const val ISO_OBJECT = 3
    const val OBJECT_CONTAINER = 4
    const val OBJECT_IN_VEHICLE = 5
    const val VEHICLE = 6
    const val PLAYER_INVENTORY = 7
    const val INVENTORY_CONTAINER = 8
    const val FLOOR = 9
  }
}
