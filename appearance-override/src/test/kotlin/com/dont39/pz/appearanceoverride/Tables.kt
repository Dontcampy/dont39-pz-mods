package com.dont39.pz.appearanceoverride

/** A Lua table as the game hands it over: numbers as Doubles, array indices as Double keys. */
internal class MapShape(private val map: Map<Any, Any?>) : LuaShape {
  override fun get(key: String): Any? = wrap(map[key])

  override fun get(index: Int): Any? = wrap(map[index.toDouble()])

  override val keys: List<Any>
    get() = map.keys.toList()

  private fun wrap(value: Any?): Any? = if (value is Map<*, *>) table(value) else value
}

@Suppress("UNCHECKED_CAST")
internal fun table(map: Map<*, *>) = MapShape(map as Map<Any, Any?>)

internal fun table(vararg entries: Pair<Any, Any?>) = MapShape(mapOf(*entries))

internal fun array(vararg values: Any?) = values.withIndex().associate { (i, v) -> (i + 1).toDouble() to v }

internal fun look(
  type: Any? = "Base.Jacket_Black",
  textureChoice: Any? = 0.0,
  baseTexture: Any? = -1.0,
  tint: Any? = array(0.5, 0.25, 1.0, 1.0),
  decal: Any? = null,
  hue: Any? = null,
): Map<Any, Any?> = listOf(
  "type" to type,
  "textureChoice" to textureChoice,
  "baseTexture" to baseTexture,
  "tint" to tint,
  "decal" to decal,
  "hue" to hue,
).filter { it.second != null }.toMap()
