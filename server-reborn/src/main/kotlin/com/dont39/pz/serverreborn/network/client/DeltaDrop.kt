package com.dont39.pz.serverreborn.network.client

import com.dont39.pz.serverreborn.ServerReborn
import com.dont39.pz.serverreborn.network.game.Containers
import com.dont39.pz.serverreborn.network.game.PacketScope
import com.dont39.pz.serverreborn.network.game.Settings
import zombie.core.network.ByteBufferReader
import zombie.network.GameClient
import zombie.network.PacketTypes.PacketType
import zombie.network.fields.ContainerID
import zombie.network.fields.character.PlayerID

/**
 * `-Dserverreborn.debugDropDelta=n` (AC1): drops every n-th container or item update the game receives while the
 * client checks, before the game reads it, as a lost update would be. `-Dserverreborn.debugDropModule=m[.command]`
 * (AC11) drops a mod's server commands the same way, every n-th of them or every one without debugDropDelta. Main
 * thread only.
 */
internal object DeltaDrop {
  private var seen = 0L
  private var seenCommands = 0L

  /** True to drop the packet; called for every packet the client handles in game. */
  fun drop(type: PacketType, input: ByteBufferReader?): Boolean {
    if (Settings.dropModule != null && type == PacketType.ClientCommand && ClientSync.checking) {
      return dropCommand(input!!)
    }
    val every = Settings.dropDelta
    if (every <= 0 || !ClientSync.checking) return false
    val kind = PacketScope.kind(type) ?: return false
    if (++seen % every != 0L) return false
    ServerReborn.warn { "debug: dropped incoming ${type.name} (update $seen) for ${target(kind, input!!)}" }
    ClientSync.dropped()
    return true
  }

  // a server command starts with its module and command name (GameClient.receiveClientCommand)
  private fun dropCommand(input: ByteBufferReader): Boolean {
    val r = ByteBufferReader(input.bb.duplicate())
    val module = r.getUTF()
    if (module != Settings.dropModule) return false
    val command = r.getUTF()
    if (Settings.dropCommand != null && command != Settings.dropCommand) return false
    if (++seenCommands % maxOf(Settings.dropDelta, 1) != 0L) return false
    ServerReborn.warn { "debug: dropped incoming server command $module.$command ($seenCommands)" }
    ClientSync.dropped()
    return true
  }

  // reads the start of the packet as its parse does, from a copy of the reader
  private fun target(kind: PacketScope.Kind, input: ByteBufferReader): String {
    val r = ByteBufferReader(input.bb.duplicate())
    return try {
      if (kind == PacketScope.Kind.ITEM_FIELDS || kind == PacketScope.Kind.WEAPON_FIELDS) {
        PlayerID().parse(r, GameClient.connection)
      }
      val id = ContainerID()
      id.parse(r, GameClient.connection)
      val where = Containers.keyOf(id)?.toString() ?: "a floor container"
      when (kind) {
        PacketScope.Kind.ITEM_FIELDS, PacketScope.Kind.WEAPON_FIELDS, PacketScope.Kind.ITEM_MOD_DATA,
        PacketScope.Kind.ITEM_STATS,
        -> "item ${r.getInt()} in $where"
        PacketScope.Kind.REMOVE, PacketScope.Kind.DELETE -> "items ${ids(r)} in $where"
        PacketScope.Kind.REPLACE -> "item ${r.getInt()} replaced in $where"
        PacketScope.Kind.ADD -> where
      }
    } catch (e: RuntimeException) {
      // the description only; the packet is dropped either way
      "a target the mod could not read ($e)"
    }
  }

  private fun ids(r: ByteBufferReader): List<Int> = List(maxOf(r.getShort().toInt(), 0)) { r.getInt() }
}
