package com.dont39.pz.appearanceoverride

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PlayerSetTest {
  private val hidden = mapOf<Any, Any?>("hidden" to true)

  private fun set(revision: Double, vararg entries: Pair<String, Map<Any, Any?>>) =
    PlayerSet.parse(revision, table(mapOf(*entries)))

  @Test
  fun `entries are keyed by the worn item's type`() {
    val parsed = set(3.0, "Base.Bag_Sprayer" to hidden, "Base.Jacket_Padded" to look())
    assertEquals(3L, parsed.revision)
    assertEquals(Override.Hidden, parsed.overrides["Base.Bag_Sprayer"])
    assertEquals("Base.Jacket_Black", (parsed.overrides["Base.Jacket_Padded"] as Override.Look).type)
  }

  @Test
  fun `a bad entry names its item type`() {
    val message = assertThrows<IllegalArgumentException> {
      set(1.0, "Base.Jacket_Padded" to look(type = 3.0))
    }.message!!
    assertTrue(message.startsWith("Base.Jacket_Padded: type is 3.0"), message)
    assertThrows<IllegalArgumentException> { PlayerSet.parse(1.0, table("Jacket" to hidden)) }
    assertThrows<IllegalArgumentException> { PlayerSet.parse(1.0, table("Base.Jacket" to true)) }
  }

  @Test
  fun `a revision is a whole number from 1`() {
    assertThrows<IllegalArgumentException> { set(0.0) }
    assertThrows<IllegalArgumentException> { set(1.5) }
    assertEquals(0, set(1.0).overrides.size)
  }

  @Test
  fun `only a newer set replaces the one held`() {
    val held = set(5.0, "Base.Hat" to hidden)
    assertTrue(held.replaces(null))
    assertTrue(set(6.0).replaces(held))
    assertFalse(set(5.0).replaces(held))
    assertFalse(set(4.0, "Base.Shoes" to hidden).replaces(held))
  }

  @Test
  fun `a rebuild is needed only when the drawn overrides change`() {
    val held = set(5.0, "Base.Hat" to hidden, "Base.Jacket_Padded" to look())
    assertFalse(set(6.0, "Base.Jacket_Padded" to look(), "Base.Hat" to hidden).drawsOtherwise(held))
    assertTrue(set(6.0, "Base.Hat" to hidden).drawsOtherwise(held))
    assertTrue(set(6.0, "Base.Hat" to hidden, "Base.Jacket_Padded" to look(textureChoice = 1.0)).drawsOtherwise(held))
    assertTrue(set(1.0, "Base.Hat" to hidden).drawsOtherwise(null))
    assertFalse(set(1.0).drawsOtherwise(null))
  }
}
