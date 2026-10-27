package com.dont39.pz.appearanceoverride

/** One player's overrides as the server last sent them, by the worn item's type. */
internal class PlayerSet(val revision: Long, val overrides: Map<String, Override>) {
  /** Server commands arrive reliably but unordered: only a newer set replaces [current]. */
  fun replaces(current: PlayerSet?): Boolean = current == null || revision > current.revision

  /** Whether the player is drawn otherwise than with [current]: its model needs a rebuild. */
  fun drawsOtherwise(current: PlayerSet?): Boolean = overrides != (current?.overrides ?: emptyMap<String, Override>())

  companion object {
    // the largest whole number a Lua number (double) holds exactly
    private const val MAX_REVISION = 9_007_199_254_740_992.0

    /**
     * [entries]: `{ ["Module.Type"] = <override>, ... }`, keyed by the worn item's type, as the server Lua sends it.
     * Throws [IllegalArgumentException] naming what does not match.
     */
    fun parse(revision: Double, entries: LuaShape): PlayerSet {
      require(revision == Math.floor(revision) && revision in 1.0..MAX_REVISION) {
        "revision $revision is not a whole number from 1"
      }
      val overrides = entries.keys.associate { key ->
        require(key is String && '.' in key) { "entry $key is not an item type with its module (Base.Name)" }
        val value = entries[key]
        require(value is LuaShape) { "$key: $value is not an override table" }
        val override = try {
          Override.parse(value)
        } catch (e: IllegalArgumentException) {
          throw IllegalArgumentException("$key: ${e.message}", e)
        }
        key to override
      }
      return PlayerSet(revision.toLong(), overrides)
    }
  }
}
