package com.dont39.pz.appearanceoverride

import com.dont39.pz.zbktutils.glue.LuaGlobal
import java.util.Collections
import java.util.WeakHashMap
import se.krka.kahlua.vm.KahluaTable
import zombie.core.skinnedmodel.visual.ItemVisual
import zombie.inventory.InventoryItem

/** Overrides as kept in the items' mod data: what corpses, mannequins and previews built from items are drawn from. */
internal object ItemOverrides {
  /** What [value], the item's mod data entry, draws; null when it cannot be drawn. */
  private class Read(val value: Any, val drawn: Drawn?)

  // checked once per entry, so a bad one is reported once
  private val read = Collections.synchronizedMap(WeakHashMap<InventoryItem, Read>())

  /** The Lua API's check: null when [table] can be stored and drawn as an override, else why not. */
  @LuaGlobal("Dont39AllInOne_checkAppearanceOverride")
  fun luaCheck(table: KahluaTable?): String? {
    AppearanceOverride.whyNotActive()?.let { return "appearance-override is not running: $it" }
    return try {
      problem(table)
    } catch (t: Throwable) {
      AppearanceOverride.disable("checking an override", t)
      "appearance-override is not running: it was disabled after an error (details in console.txt)"
    }
  }

  private fun problem(value: Any?): String? = try {
    valid(value)
    null
  } catch (e: IllegalArgumentException) {
    e.message
  }

  /** Throws [IllegalArgumentException] naming what does not match. */
  private fun valid(value: Any?): Override {
    require(value is KahluaTable) { "the override is $value, not a table" }
    val override = Override.parse(KahluaShape(value))
    if (override is Override.Look) {
      Looks.problem(override)?.let { throw IllegalArgumentException(it) }
    }
    return override
  }

  /** In place of [worn], or null to draw it as itself. */
  fun drawn(worn: ItemVisual): Drawn? {
    val item = worn.inventoryItem ?: return null
    if (!item.hasModData()) return null
    val value = item.modData.rawget(Override.KEY) ?: return null
    read[item]?.takeIf { it.value === value }?.let { return it.drawn }
    // stored data, like a settings file: a type whose mod was removed must not stop the feature for everyone
    val drawn = try {
      Drawn.of(valid(value))
    } catch (e: IllegalArgumentException) {
      AppearanceOverride.error {
        "${item.fullType} (item ${item.id}) is drawn as itself: its override is not valid: ${e.message}"
      }
      null
    }
    read[item] = Read(value, drawn)
    return drawn
  }
}
