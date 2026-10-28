package com.dont39.pz.appearanceoverride

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class OverrideTest {
  private fun refused(map: Map<Any, Any?>): String =
    assertThrows<IllegalArgumentException> { Override.parse(table(map)) }.message!!

  @Test
  fun `hidden is only hidden`() {
    assertEquals(Override.Hidden, Override.parse(table("hidden" to true)))
    assertTrue("not true" in refused(mapOf("hidden" to false)))
    assertTrue("other keys" in refused(mapOf("hidden" to true, "type" to "Base.Hat")))
  }

  @Test
  fun `a look keeps every value`() {
    val parsed = Override.parse(table(look(textureChoice = 2.0, baseTexture = 1.0, decal = "Logo", hue = -0.5)))
    assertEquals(
      Override.Look("Base.Jacket_Black", 2, 1, Tint(0.5f, 0.25f, 1f, 1f), "Logo", -0.5f),
      parsed,
    )
  }

  @Test
  fun `a look without decal or hue has none`() {
    val parsed = Override.parse(table(look())) as Override.Look
    assertEquals(null, parsed.decal)
    assertEquals(0f, parsed.hue)
  }

  @Test
  fun `a look with a value missing or of the wrong kind is refused`() {
    assertTrue("type is null" in refused(look().minus("type")))
    assertTrue("with its module" in refused(look(type = "Jacket")))
    assertTrue("textureChoice is null" in refused(look().minus("textureChoice")))
    assertTrue("whole number" in refused(look(textureChoice = 1.5)))
    assertTrue("whole number" in refused(look(baseTexture = -2.0)))
    assertTrue("tint is null" in refused(look().minus("tint")))
    assertTrue("not { r, g, b, a }" in refused(look(tint = array(1.0, 1.0, 1.0))))
    assertTrue("tint[2]" in refused(look(tint = array(1.0, 1.5, 1.0, 1.0))))
    assertTrue("tint[4]" in refused(look(tint = array(1.0, 1.0, 1.0, "1"))))
    assertTrue("hue" in refused(look(hue = 2.0)))
    assertTrue("decal" in refused(look(decal = 3.0)))
  }

  @Test
  fun `the stored table reads back as the same override`() {
    listOf(
      Override.Hidden,
      Override.parse(table(look())),
      Override.parse(table(look(textureChoice = 2.0, baseTexture = 1.0, decal = "Logo", hue = -0.5))),
      Override.parse(table(look(tint = array(0.3, 0.1, 0.7, 1.0), hue = 0.1))),
    ).forEach { assertEquals(it, Override.parse(table(it.toTable()))) }
  }

  @Test
  fun `the stored table holds each value as drawn`() {
    val stored = Override.parse(table(look(tint = array(0.3, 0.25, 1.0, 1.0)))).toTable()
    assertEquals(mapOf("hidden" to true), Override.Hidden.toTable())
    assertEquals(setOf("type", "textureChoice", "baseTexture", "tint", "hue"), stored.keys)
    // a missing hue is 0; the tint as the float the visual gets
    assertEquals(0.0, stored["hue"])
    assertEquals(array(0.3f.toDouble(), 0.25, 1.0, 1.0), stored["tint"])
    assertEquals(-1.0, stored["baseTexture"])
  }

  @Test
  fun `the text is the same exactly when the override draws the same`() {
    fun text(map: Map<Any, Any?>) = Override.parse(table(map)).text
    val base = text(look())
    assertEquals(base, text(look(hue = 0.0)))
    assertEquals(base, Override.parse(table(Override.parse(table(look())).toTable())).text)
    listOf(
      look(type = "Base.Jacket_White"),
      look(textureChoice = 1.0),
      look(baseTexture = 0.0),
      look(tint = array(0.5, 0.25, 1.0, 0.5)),
      look(decal = "Logo"),
      look(hue = 0.5),
    ).forEach { assertNotEquals(base, text(it), it.toString()) }
    assertNotEquals(base, Override.Hidden.text)
  }

  @Test
  fun `unknown keys are refused`() {
    assertTrue("texturechoice" in refused(look() + ("texturechoice" to 1.0)))
    assertTrue("unknown keys 1.0" in refused(look() + (1.0 to "x")))
  }
}
