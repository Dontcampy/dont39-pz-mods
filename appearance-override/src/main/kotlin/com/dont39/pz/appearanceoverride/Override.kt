package com.dont39.pz.appearanceoverride

/** A Lua table as the parsers read it: the game's table in the mod, a map in the tests. */
internal interface LuaShape {
  /** A string, a number (Double), a boolean, a nested [LuaShape], or null. */
  operator fun get(key: String): Any?

  operator fun get(index: Int): Any?

  /** Strings, and numbers as Doubles. */
  val keys: List<Any>
}

/**
 * How a worn item is drawn instead of as itself. Kept in the item's mod data under [KEY]; the Lua API
 * (`Dont39AllInOne_AppearanceOverrideShared.lua`) writes it in the format [parse] reads.
 */
internal sealed interface Override {
  data object Hidden : Override

  /** Drawn as [type]. Every value a getter of the game's visual would otherwise pick at random is given. */
  data class Look(
    val type: String,
    val textureChoice: Int,
    val baseTexture: Int,
    val tint: Tint,
    val decal: String?,
    val hue: Float,
  ) : Override

  companion object {
    const val KEY = "Dont39AllInOne_Appearance"

    private const val HIDDEN = "hidden"
    private const val TYPE = "type"
    private const val TEXTURE_CHOICE = "textureChoice"
    private const val BASE_TEXTURE = "baseTexture"
    private const val TINT = "tint"
    private const val DECAL = "decal"
    private const val HUE = "hue"
    private val LOOK_KEYS = setOf(TYPE, TEXTURE_CHOICE, BASE_TEXTURE, TINT, DECAL, HUE)

    /**
     * `{ hidden = true }`, or `{ type = "Module.Type", textureChoice = n, baseTexture = n, tint = { r, g, b, a },
     * decal = s|nil, hue = f|nil }` (tint 0 to 1, hue -1 to 1, nil is 0). Throws [IllegalArgumentException] naming
     * what does not match.
     */
    fun parse(table: LuaShape): Override {
      val unknown = table.keys.filter { it !is String || (it != HIDDEN && it !in LOOK_KEYS) }
      require(unknown.isEmpty()) { "unknown keys ${unknown.joinToString()}" }
      if (table[HIDDEN] != null) {
        require(table[HIDDEN] == true) { "$HIDDEN is ${table[HIDDEN]}, not true" }
        require(table.keys.size == 1) { "$HIDDEN = true with other keys: ${table.keys.joinToString()}" }
        return Hidden
      }
      val type = table[TYPE]
      require(type is String && '.' in type) { "$TYPE is $type, not an item type with its module (Base.Name)" }
      val hue = table[HUE] ?: 0.0
      require(hue is Double && hue in -1.0..1.0) { "$HUE is $hue, not -1 to 1" }
      val decal = table[DECAL]
      require(decal == null || decal is String) { "$DECAL is $decal, not a string" }
      return Look(
        type,
        index(table, TEXTURE_CHOICE),
        index(table, BASE_TEXTURE),
        tint(table[TINT]),
        decal as String?,
        hue.toFloat(),
      )
    }

    // -1 when the type has no choices, as the game stores it
    private fun index(table: LuaShape, key: String): Int {
      val value = table[key]
      require(value is Double && value == Math.floor(value) && value in -1.0..Int.MAX_VALUE.toDouble()) {
        "$key is $value, not a whole number from -1"
      }
      return value.toInt()
    }

    private fun tint(value: Any?): Tint {
      require(value is LuaShape && value.keys.size == 4) { "$TINT is $value, not { r, g, b, a }" }
      val parts = (1..4).map { i ->
        val part = value[i]
        require(part is Double && part in 0.0..1.0) { "$TINT[$i] is $part, not 0 to 1" }
        part.toFloat()
      }
      return Tint(parts[0], parts[1], parts[2], parts[3])
    }
  }
}

internal data class Tint(val r: Float, val g: Float, val b: Float, val a: Float)
