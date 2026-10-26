package com.dont39.pz.serverreborn.networksyncoptimization.game

import com.dont39.pz.serverreborn.networksyncoptimization.protocol.ContainerDigest
import com.dont39.pz.serverreborn.networksyncoptimization.protocol.Digest
import com.dont39.pz.serverreborn.networksyncoptimization.protocol.LuaTableHash
import com.dont39.pz.serverreborn.networksyncoptimization.protocol.Unordered
import se.krka.kahlua.j2se.KahluaTableImpl
import se.krka.kahlua.vm.KahluaTable
import zombie.characterTextures.BloodBodyPartType
import zombie.core.Color
import zombie.entity.components.fluids.FluidContainer
import zombie.inventory.InventoryItem
import zombie.inventory.ItemContainer
import zombie.inventory.types.Clothing
import zombie.inventory.types.DrainableComboItem
import zombie.inventory.types.Food
import zombie.inventory.types.HandWeapon
import zombie.inventory.types.Literature
import zombie.inventory.types.WeaponPart
import java.nio.ByteBuffer

/**
 * The digests both sides compare: over the item state the game's field packets carry and the client takes only from
 * them (SyncItemFields, ItemStats, SyncItemModData, SyncHandWeaponFields, as their `setData` reads it).
 *
 * Left out, each because the two sides legitimately differ or a field payload cannot repair it: heat and item heat,
 * age, freezing time and frozen (simulated on both sides, never sent; the client's inventory pane ages food itself);
 * fertilized and its time (the client's freezing update clears it); cooking time (Food.update sends before it advances
 * it); wet cooldown (counts down on both sides); clothing wetness and dirtiness (own packet, and the client updates
 * them); attached slot, slot type and model (the hotbar resets them on the client without a sync); tainted water
 * (setData never sets it); activated, key id, stash map, infection, world scale and rotations (only in the full save,
 * not in a field packet); a name that is not custom (each side translates it into its own language); food stats and
 * clothing weight derived from those; a food's actual weight (follows its name); a sharp weapon's maximum damage (the
 * packet sends it scaled); mod data pairs one side writes for itself (see [KAHLUA]). Values the packets narrow (colour,
 * poison) are hashed as they arrive. Main thread only: one scratch buffer.
 */
internal object ItemDigests {
  /**
   * Fields that may differ between the sides in normal play for reasons not settled from the bytecode; the in-game
   * per-field diff run decides each. All are in until it drops one; both sides must agree, so a change bumps
   * `Protocol.VERSION`.
   */
  enum class Checked {
    /** Ranged weapon wear on the client is not proven; break-when-wet clothing breaks in the client's pane. */
    CONDITION,
    HEAD_CONDITION,

    /** Activated drainables drain on both sides; left out while activated in any case. */
    USES,

    /** Fired and reloaded on the client first, in flight to the server at the frame. */
    AMMO,

    /** Combat may bloody weapons and clothing on the client. */
    BLOOD,

    /** Clothing blood, dirt and holes, as for [BLOOD]. */
    VISUAL,

    /** May be derived from contents or fluids. */
    WEIGHT,

    /** The reading path was not traced. */
    PAGES,

    /** Melee wear's side is not proven. */
    SHARPNESS,

    /** Mods may write item mod data on the client without a sync. */
    MOD_DATA,

    /** Set after the minute's ItemStats; the next minute's carries it. */
    COOKED_BURNT,

    /** Flips on both sides when the cooldown ends. */
    WET,
  }

  private val CHECKED = Checked.entries.toSet()
  private val FLUIDS = ByteBuffer.allocate(64 * 1024)
  private val PARTS = ArrayList<WeaponPart>()

  /** Mod data tables deeper than this are described by their hash. */
  private const val DESCRIBED_DEPTH = 3

  private val KAHLUA = object : LuaTableHash.Tables {
    override fun isTable(value: Any?) = value is KahluaTable

    override fun forEach(table: Any, pair: (Any?, Any?) -> Unit) {
      val it = (table as KahluaTable).iterator()
      while (it.advance()) {
        pair(it.key, it.value)
      }
    }

    override fun saved(key: Any?, value: Any?) = KahluaTableImpl.canSave(key, value)

    override fun ownPerSide(key: Any?, value: Any?) = when (key) {
      // InventoryItem.setCustomName(boolean) writes the item's name before the change, in each side's language;
      // ItemStats and SyncItemFields call it on every apply, so a snapshot rewrites it
      "customName" -> true
      // RPGInventory's loot search marks items the client's player has seen (LootSearchRules.reveal)
      "RPGInventoryDiscovered" -> true
      // RPGInventory's client rewrites a bag's rules from its cache of the server's answer, with empty tables where
      // the server's record has none (TransferConfig.lua)
      "RPGInventoryTransferRules" -> true
      // Clothing.getUsedDelta() writes 0 when absent, on whichever side reads it (client Lua and tooltips); a value
      // set through setUsedDelta still counts
      "usedDelta" -> value is Double && value == 0.0
      else -> false
    }
  }

  private fun on(field: Checked) = field in CHECKED

  /** Bags' contents are their own containers. [digest] decides each item's digest from its id and own digest. */
  fun container(container: ItemContainer, digest: (Int, Long) -> Long = { _, own -> own }): Long {
    val d = ContainerDigest()
    container.getItems().forEach { d.add(it.getID(), it.fullType, digest(it.getID(), item(it))) }
    return d.get()
  }

  fun item(item: InventoryItem): Long {
    val all = Group(null, "")
    write(item) { all }
    return all.digest.get()
  }

  /** One item's digest fields for the field diff: each group's hash, and every value by name. */
  class Fields(val hashes: Map<String, Long>, val values: Map<String, String>)

  /**
   * The same values as [item], hashed per field group instead of into one digest, and kept by name as `group.field`
   * (mod data as `modData.<key path>`), for `-Dserverreborn.debugFieldDiff`.
   */
  fun fields(item: InventoryItem): Fields {
    val groups = LinkedHashMap<String, Group>()
    val values = LinkedHashMap<String, String>()
    write(item) { field -> groups.getOrPut(field) { Group(values, field) } }
    return Fields(groups.mapValuesTo(LinkedHashMap()) { it.value.digest.get() }, values)
  }

  /** One group's digest; while describing ([values] not null), also each value by name. */
  private class Group(private val values: MutableMap<String, String>?, group: String) {
    val digest = Digest()
    private val prefix = "$group."

    val describing get() = values != null

    fun note(name: String, value: String) {
      values?.put(prefix + name, value)
    }

    fun i(name: String, v: Int) = apply {
      digest.putInt(v)
      if (describing) note(name, v.toString())
    }

    fun l(name: String, v: Long) = apply {
      digest.putLong(v)
      if (describing) note(name, "%016x".format(v))
    }

    fun f(name: String, v: Float) = apply {
      digest.putFloat(v)
      if (describing) note(name, v.toString())
    }

    fun b(name: String, v: Boolean) = apply {
      digest.putBoolean(v)
      if (describing) note(name, v.toString())
    }

    fun s(name: String, v: String?) = apply {
      digest.putString(v)
      if (describing) note(name, if (v == null) "null" else "\"$v\"")
    }

    fun bytes(name: String, bytes: ByteArray, length: Int) = apply {
      digest.putBytes(bytes, 0, length)
      if (describing) note(name, "%d bytes #%016x".format(length, Digest().putBytes(bytes, 0, length).get()))
    }
  }

  /** [out]: the group a field's values go to, one for all or one per field. */
  private fun write(item: InventoryItem, out: (String) -> Group) {
    out("type").s("fullType", item.fullType)
    if (on(Checked.CONDITION)) {
      out("condition").i("condition", item.condition)
    }
    if (on(Checked.HEAD_CONDITION)) {
      val g = out("headCondition")
      g.b("has", item.hasHeadCondition())
      if (item.hasHeadCondition()) {
        g.i("value", item.headCondition)
      }
    }
    out("remoteControlId").i("id", item.remoteControlID)
    if (on(Checked.USES) && !item.isActivated) {
      val g = out("uses")
      g.i("current", item.currentUses)
      if (item is DrainableComboItem) {
        g.f("float", item.currentUsesFloat)
      }
    }
    if (on(Checked.AMMO)) {
      out("ammo").i("count", item.currentAmmoCount)
    }
    out("repaired").i("times", item.haveBeenRepaired)
    // clothing's weight follows its wetness, which is left out; see food() for a food's
    if (on(Checked.WEIGHT) && item !is Clothing && !(item is Food && nameWeighted(item))) {
      out("weight").f("weight", item.weight)
    }
    out("favorite").b("favorite", item.isFavorite)
    val name = out("customName")
    name.b("custom", item.isCustomName)
    if (item.isCustomName) {
      name.s("name", item.name)
    }
    val color = out("customColor")
    color.b("custom", item.isCustomColor)
    if (item.isCustomColor) {
      // SyncItemFields carries it as 8 bits per channel
      color.i("abgr", Color.colorToABGR(item.color))
    }
    if (on(Checked.BLOOD) && (item is Clothing || item is HandWeapon)) {
      out("blood").f("level", item.bloodLevel)
    }
    val sharpness = out("sharpness")
    sharpness.b("has", item.hasSharpness())
    if (on(Checked.SHARPNESS) && item.hasSharpness()) {
      sharpness.f("value", item.sharpness)
    }
    if (on(Checked.MOD_DATA)) {
      val g = out("modData")
      val table = if (item.hasModData()) item.modData else null
      g.digest.putLong(if (table != null) LuaTableHash.hash(table, KAHLUA) else LuaTableHash.EMPTY)
      if (g.describing && table != null) {
        LuaTableHash.describe(table, KAHLUA, DESCRIBED_DEPTH, g::note)
      }
    }
    if (on(Checked.WET)) {
      out("wet").b("wet", item.isWet)
    }
    fluids(out("fluids"), item.fluidContainer)
    if (item is Clothing) clothing(out, item)
    if (item is Food) food(out, item)
    if (item is Literature) literature(out, item)
    if (item is HandWeapon) weapon(out, item)
  }

  // as SyncItemFields carries it: the container's own save
  private fun fluids(g: Group, fluids: FluidContainer?) {
    g.b("has", fluids != null)
    if (fluids == null) return
    FLUIDS.clear()
    fluids.save(FLUIDS)
    g.bytes("saved", FLUIDS.array(), FLUIDS.position())
  }

  private fun clothing(out: (String) -> Group, clothing: Clothing) {
    val parts = BloodBodyPartType.MAX.index()
    val patches = Unordered()
    for (i in 0 until parts) {
      val patch = clothing.getPatchType(BloodBodyPartType.FromIndex(i)) ?: continue
      patches.add(
        Digest.of(i.toLong(), patch.tailorLvl.toLong(), patch.fabricType.toLong(), if (patch.hasHole) 1L else 0L),
      )
    }
    out("patches").l("set", patches.get())
    val visual = clothing.visual?.takeIf { on(Checked.VISUAL) }
    val g = out("visual")
    g.b("has", visual != null)
    if (visual != null) {
      for (i in 0 until parts) {
        val part = BloodBodyPartType.FromIndex(i)
        val p = part.name
        g.f("$p.basic", visual.getBasicPatch(part)).f("$p.denim", visual.getDenimPatch(part))
          .f("$p.leather", visual.getLeatherPatch(part)).f("$p.hole", visual.getHole(part))
          .f("$p.dirt", visual.getDirt(part)).f("$p.blood", visual.getBlood(part))
      }
    }
  }

  // Food.getWeight() is getActualWeight() then
  private fun nameWeighted(food: Food) = food.replaceOnUse != null

  // thirst, unhappiness, boredom, stress and food sickness are left out: their getters scale them by age, heat,
  // freezing and cooking, and ItemStats stores the server's scaled value as the client's base. So is the actual
  // weight: getActualWeightUnmodded() returns 0 for an item whose name equals its full type, as the dedicated server
  // has it for some mod foods while the client has a translated name, until an ItemStats copies the server's name over
  private fun food(out: (String) -> Group, food: Food) {
    out("food").f("hungChange", food.hungChange)
      .f("calories", food.calories).f("carbohydrates", food.carbohydrates)
      .f("lipids", food.lipids).f("proteins", food.proteins)
      .i("fluReduction", food.fluReduction).f("painReduction", food.painReduction)
      .f("endChange", food.endChange)
      .f("fatigueChange", food.getFatigueChange())
      // ItemStats writes both as a byte, and leaves out a detection level of -1 ("none"), which the client then
      // reads as 0
      .i("poisonPower", food.poisonPower.toByte().toInt())
      .i(
        "poisonDetectionLevel",
        if (food.poisonDetectionLevel == -1) 0 else food.poisonDetectionLevel.toByte().toInt(),
      )
      .b("alcoholic", food.isAlcoholic)
      .f("baseHunger", food.baseHunger)
      .f("minutesToCook", food.minutesToCook).f("minutesToBurn", food.minutesToBurn)
      .b("tainted", food.isTainted)
    // getters, not the public fields of the same names that property syntax would read
    strings(out("extraItems"), food.getExtraItems())
    strings(out("spices"), food.getSpices())
    if (on(Checked.COOKED_BURNT)) {
      out("cookedBurnt").b("cooked", food.isCooked).b("burnt", food.isBurnt)
    }
  }

  // none and empty alike: ItemStats creates an empty list on the client where the server may have none
  private fun strings(g: Group, list: List<String?>?) {
    val values = list.orEmpty()
    g.i("size", values.size)
    values.forEachIndexed { i, v -> g.s(i.toString(), v) }
  }

  private fun literature(out: (String) -> Group, literature: Literature) {
    if (on(Checked.PAGES)) {
      out("pages").i("read", literature.alreadyReadPages)
    }
    val pages = literature.customPages
    val g = out("customPages")
    g.b("has", pages != null)
    if (pages != null) {
      val u = Unordered()
      pages.forEach { (page, text) -> u.add(Digest().putInt(page).putString(text).get()) }
      g.l("set", u.get())
    }
  }

  private fun weapon(out: (String) -> Group, weapon: HandWeapon) {
    if (on(Checked.AMMO)) {
      out("weaponAmmo").b("roundChambered", weapon.isRoundChambered)
        .b("containsClip", weapon.isContainsClip).i("spentRounds", weapon.spentRoundCount)
        .b("spentRoundChambered", weapon.isSpentRoundChambered).b("jammed", weapon.isJammed)
    }
    val stats = out("weaponStats").f("maxRange", weapon.maxRange)
      .f("minRangeRanged", weapon.minRangeRanged)
      .i("clipSize", weapon.clipSize).i("reloadTime", weapon.reloadTime)
      .i("recoilDelay", weapon.recoilDelay).i("aimingTime", weapon.aimingTime)
      .i("hitChance", weapon.hitChance).f("minAngle", weapon.minAngle)
      .f("minDamage", weapon.minDamage)
    // getMaxDamage() scales by sharpness; SyncHandWeaponFields sends the scaled value, which the client stores as its
    // base and scales again
    if (!weapon.hasSharpness()) {
      stats.f("maxDamage", weapon.maxDamage)
    }
    PARTS.clear()
    weapon.getAllWeaponParts(PARTS)
    val parts = Unordered()
    PARTS.forEach { parts.add(Digest().putString(it.fullType).get()) }
    PARTS.clear()
    out("weaponParts").l("set", parts.get())
  }
}
