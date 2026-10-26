package com.dont39.pz.serverreborn.networksyncoptimization.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.assertThrows

class DigestTest {
  /** Maps as Lua tables; a "fn" value stands for one the game does not save. */
  private open class Maps : LuaTableHash.Tables {
    override fun isTable(value: Any?) = value is Map<*, *>

    override fun forEach(table: Any, pair: (Any?, Any?) -> Unit) = (table as Map<*, *>).forEach { (k, v) -> pair(k, v) }

    override fun saved(key: Any?, value: Any?) = value != "fn"
  }

  private val maps = Maps()

  private fun hash(table: Map<*, *>, tables: LuaTableHash.Tables = maps) = LuaTableHash.hash(table, tables)

  @Test
  fun `same values give the same digest`() {
    assertEquals(
      Digest().putInt(1).putString("a").putFloat(0.5f).get(),
      Digest().putInt(1).putString("a").putFloat(0.5f).get(),
    )
  }

  @Test
  fun `order matters in a sequence`() {
    assertNotEquals(Digest().putInt(1).putInt(2).get(), Digest().putInt(2).putInt(1).get())
  }

  @Test
  fun `boundaries matter`() {
    assertNotEquals(Digest().putString("ab").putString("c").get(), Digest().putString("a").putString("bc").get())
    assertNotEquals(Digest().putString(null).get(), Digest().putString("").get())
    assertNotEquals(Digest().get(), Digest().putLong(0).get())
  }

  @Test
  fun `bytes of every length differ`() {
    val data = ByteArray(20)
    assertEquals(data.size + 1, (0..data.size).map { Digest().putBytes(data, 0, it).get() }.toSet().size)
  }

  @Test
  fun `floats by bits`() {
    assertEquals(Digest().putFloat(Float.NaN).get(), Digest().putFloat(Float.fromBits(0x7FC00001)).get())
    assertNotEquals(Digest().putFloat(0f).get(), Digest().putFloat(-0f).get())
  }

  @Test
  fun `a container digest ignores item order`() {
    assertEquals(
      ContainerDigest().add(1, "Base.Apple", 10).add(2, "Base.Axe", 20).add(3, "Base.Bag", 30).get(),
      ContainerDigest().add(3, "Base.Bag", 30).add(1, "Base.Apple", 10).add(2, "Base.Axe", 20).get(),
    )
  }

  @TestFactory
  fun `a container digest covers id, type, state and count`(): List<DynamicTest> {
    val base = ContainerDigest().add(1, "Base.Apple", 10).add(2, "Base.Axe", 20).get()
    return listOf(
      "another id" to ContainerDigest().add(9, "Base.Apple", 10).add(2, "Base.Axe", 20),
      "another type" to ContainerDigest().add(1, "Base.Pear", 10).add(2, "Base.Axe", 20),
      "another state" to ContainerDigest().add(1, "Base.Apple", 11).add(2, "Base.Axe", 20),
      "one item fewer" to ContainerDigest().add(1, "Base.Apple", 10),
      "an item twice" to ContainerDigest().add(1, "Base.Apple", 10).add(2, "Base.Axe", 20).add(2, "Base.Axe", 20),
      "empty" to ContainerDigest(),
    ).map { (name, other) -> DynamicTest.dynamicTest(name) { assertNotEquals(base, other.get()) } }
  }

  // the pair is hashed together, so two items cannot trade their states unnoticed
  @Test
  fun `swapped states differ`() {
    assertNotEquals(
      ContainerDigest().add(1, "Base.Apple", 10).add(2, "Base.Apple", 20).get(),
      ContainerDigest().add(1, "Base.Apple", 20).add(2, "Base.Apple", 10).get(),
    )
  }

  @Test
  fun `Lua tables ignore pair order`() {
    val a = linkedMapOf<Any, Any>("name" to "x", 1.0 to true, "nested" to linkedMapOf("k" to 2.0))
    val b = linkedMapOf<Any, Any>("nested" to linkedMapOf("k" to 2.0), 1.0 to true, "name" to "x")
    assertEquals(hash(a), hash(b))
  }

  @TestFactory
  fun `Lua tables cover keys, values and nesting`(): List<DynamicTest> {
    val base = hash(mapOf("k" to 1.0))
    return listOf(
      "another value" to mapOf("k" to 2.0),
      "another key" to mapOf("j" to 1.0),
      "a string for a number" to mapOf("k" to "1"),
      "a table for a number" to mapOf("k" to mapOf("k" to 1.0)),
    ).map { (name, other) -> DynamicTest.dynamicTest(name) { assertNotEquals(base, hash(other)) } }.plus(
      // one Lua number type
      DynamicTest.dynamicTest("an Integer as the same Double") { assertEquals(base, hash(mapOf("k" to 1))) },
    )
  }

  @Test
  fun `empty tables and unsaved pairs`() {
    assertEquals(LuaTableHash.EMPTY, hash(emptyMap<Any, Any>()))
    assertEquals(LuaTableHash.EMPTY, hash(mapOf("f" to "fn")))
    assertEquals(hash(mapOf("k" to 1.0)), hash(mapOf("k" to 1.0, "f" to "fn")))
  }

  @Test
  fun `a key each side writes for itself is left out at the top only`() {
    val ownName = object : Maps() {
      override fun ownPerSide(key: Any?, value: Any?) = key == "own" || (key == "lazy" && value == 0.0)
    }
    assertEquals(LuaTableHash.EMPTY, hash(mapOf("own" to "x"), ownName))
    assertEquals(hash(mapOf("k" to 1.0), ownName), hash(mapOf("k" to 1.0, "own" to "y"), ownName))
    assertNotEquals(hash(mapOf("t" to mapOf("own" to "x")), ownName), hash(mapOf("t" to mapOf("own" to "y")), ownName))
    assertEquals(LuaTableHash.EMPTY, hash(mapOf("lazy" to 0.0), ownName))
    assertNotEquals(LuaTableHash.EMPTY, hash(mapOf("lazy" to 0.5), ownName))
  }

  @Test
  fun `a cycle is an error`() {
    val loop = linkedMapOf<Any, Any>()
    loop["self"] = loop
    assertThrows<IllegalStateException> { hash(loop) }
  }

  @Test
  fun `an unknown saved value is an error`() {
    assertThrows<IllegalStateException> { hash(mapOf("k" to Any())) }
  }
}
