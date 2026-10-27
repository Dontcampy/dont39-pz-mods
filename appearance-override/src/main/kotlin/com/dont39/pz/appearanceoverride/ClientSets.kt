package com.dont39.pz.appearanceoverride

import com.dont39.pz.zbktutils.glue.LuaGlobal
import java.util.concurrent.ConcurrentHashMap
import se.krka.kahlua.vm.KahluaTable
import zombie.characters.IsoPlayer
import zombie.core.skinnedmodel.visual.ItemVisual
import zombie.network.GameClient

/**
 * Every player's overrides on this client, or in single player, as the server Lua last sent them. Players are drawn
 * from here, not from their items' mod data: other clients get a player's worn items without it, and SCTD's render
 * swap puts copies without it into the wearer's own list.
 */
internal object ClientSets {
  class Held(val set: PlayerSet) {
    private val drawn = ConcurrentHashMap<String, Drawn>()

    /** In place of [worn], or null to draw it as itself. */
    fun drawn(worn: ItemVisual): Drawn? {
      val type = worn.itemType ?: return null
      val override = set.overrides[type] ?: return null
      return drawn.computeIfAbsent(type) { Drawn.of(override) }
    }
  }

  private val byPlayer = ConcurrentHashMap<String, Held>()

  /** In multiplayer the player's user name, which the server sends; in single player the local player's slot. */
  fun keyOf(player: IsoPlayer): String = if (GameClient.client) player.username else "#${player.index}"

  /** Null when the player has no overrides. */
  fun held(player: IsoPlayer): Held? = byPlayer[keyOf(player)]

  /** Multiplayer client: a set the server sent for [user]. */
  @LuaGlobal("Dont39AllInOne_putAppearanceOverrideSet")
  fun luaPut(user: String?, revision: Double?, entries: KahluaTable?) {
    guarded("taking the overrides the server sent") {
      put(checkNotNull(user) { "no user name" }, parse(revision, entries), null)
    }
  }

  /** Single player: [player]'s set, from the server Lua of the same game. */
  @LuaGlobal("Dont39AllInOne_putLocalAppearanceOverrideSet")
  fun luaPutLocal(player: IsoPlayer?, revision: Double?, entries: KahluaTable?) {
    guarded("taking a local player's overrides") {
      checkNotNull(player) { "no player" }
      put(keyOf(player), parse(revision, entries), player)
    }
  }

  /** At the start of a game, before its sets arrive: the revisions start again with the server's Lua. */
  @LuaGlobal("Dont39AllInOne_clearAppearanceOverrideSets")
  fun luaClear() {
    byPlayer.clear()
  }

  private inline fun guarded(what: String, block: () -> Unit) {
    if (!AppearanceOverride.active()) return
    try {
      block()
    } catch (t: Throwable) {
      AppearanceOverride.disable(what, t)
    }
  }

  private fun parse(revision: Double?, entries: KahluaTable?): PlayerSet {
    checkNotNull(revision) { "no revision" }
    checkNotNull(entries) { "no entries" }
    val set = PlayerSet.parse(revision, KahluaShape(entries))
    // the server checked them against the same scripts
    set.overrides.forEach { (type, override) ->
      if (override is Override.Look) {
        Looks.problem(override)?.let { throw IllegalArgumentException("$type: $it") }
      }
    }
    return set
  }

  private fun put(key: String, set: PlayerSet, player: IsoPlayer?) {
    val changed = synchronized(this) {
      val current = byPlayer[key]?.set
      if (!set.replaces(current)) {
        return
      }
      byPlayer[key] = Held(set)
      set.drawsOtherwise(current)
    }
    if (AppearanceOverride.debug) {
      AppearanceOverride.log { "set of $key, revision ${set.revision}: ${set.overrides}" }
    }
    if (changed) {
      // a player not here yet is drawn from the set when it arrives
      (player ?: find(key))?.resetModelNextFrame()
    }
  }

  private fun find(user: String): IsoPlayer? =
    (IsoPlayer.players.asSequence() + GameClient.IDToPlayerMap.values.asSequence())
      .firstOrNull { it != null && it.username == user }
}
