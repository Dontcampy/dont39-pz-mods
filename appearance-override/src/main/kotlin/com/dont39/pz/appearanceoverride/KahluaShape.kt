package com.dont39.pz.appearanceoverride

import se.krka.kahlua.vm.KahluaTable

/** A game Lua table for the parsers. */
internal class KahluaShape(private val table: KahluaTable) : LuaShape {
  override fun get(key: String): Any? = wrap(table.rawget(key))

  override fun get(index: Int): Any? = wrap(table.rawget(index))

  override val keys: List<Any>
    get() {
      val keys = mutableListOf<Any>()
      val iterator = table.iterator()
      while (iterator.advance()) {
        keys += iterator.key
      }
      return keys
    }

  override fun toString(): String = "a table"

  private fun wrap(value: Any?): Any? = if (value is KahluaTable) KahluaShape(value) else value
}
