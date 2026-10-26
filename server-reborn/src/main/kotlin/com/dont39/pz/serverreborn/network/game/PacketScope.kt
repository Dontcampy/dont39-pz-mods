package com.dont39.pz.serverreborn.network.game

import com.dont39.pz.serverreborn.network.protocol.Protocol
import zombie.network.PacketTypes.PacketType
import zombie.network.packets.AddInventoryItemToContainerPacket
import zombie.network.packets.INetworkPacket
import zombie.network.packets.ItemStatsPacket
import zombie.network.packets.RemoveInventoryItemFromContainerPacket
import zombie.network.packets.ReplaceInventoryItemInContainerPacket
import zombie.network.packets.SyncHandWeaponFieldsPacket
import zombie.network.packets.SyncItemDeletePacket
import zombie.network.packets.SyncItemFieldsPacket
import zombie.network.packets.SyncItemModDataPacket

/** The game's packet types the mod checks, looked up by name, and their reliability and channel. */
internal object PacketScope {
  /** The packet types in scope, by the game's names. */
  enum class Kind(private val typeName: String, private val handler: Class<out INetworkPacket>) {
    ADD("AddInventoryItemToContainer", AddInventoryItemToContainerPacket::class.java),
    REMOVE("RemoveInventoryItemFromContainer", RemoveInventoryItemFromContainerPacket::class.java),
    REPLACE("ReplaceInventoryItemInContainer", ReplaceInventoryItemInContainerPacket::class.java),
    DELETE("SyncItemDelete", SyncItemDeletePacket::class.java),
    ITEM_STATS("ItemStats", ItemStatsPacket::class.java),
    ITEM_FIELDS("SyncItemFields", SyncItemFieldsPacket::class.java),
    ITEM_MOD_DATA("SyncItemModData", SyncItemModDataPacket::class.java),
    WEAPON_FIELDS("SyncHandWeaponFields", SyncHandWeaponFieldsPacket::class.java),
    ;

    lateinit var type: PacketType
      private set

    /** Throws when the game has no such type or handles it with another class than the mod was built for. */
    fun resolve(): PacketType {
      val t = try {
        PacketType.valueOf(typeName)
      } catch (e: IllegalArgumentException) {
        throw IllegalStateException("the game has no packet type $typeName", e)
      }
      check(t.handler == handler) {
        "the game handles packet type $typeName with ${t.handler} instead of ${handler.name}"
      }
      type = t
      return t
    }
  }

  // by the type's ordinal; replaced whole, read on any thread
  @Volatile
  private var kinds = emptyArray<Kind?>()
  private val undo = mutableListOf<() -> Unit>()

  /** Throws when a type is missing or handled by another class than the mod was built for. */
  @Synchronized
  fun resolve() {
    if (kinds.isNotEmpty()) return
    val byOrdinal = arrayOfNulls<Kind>(PacketType.entries.size)
    Kind.entries.forEach { byOrdinal[it.resolve().ordinal] = it }
    kinds = byOrdinal
  }

  /** Null for a type out of scope. */
  fun kind(type: PacketType): Kind? = kinds.getOrNull(type.ordinal)

  /**
   * Puts the item field packets on the container packets' ordered channel, so a field update cannot overtake its item's
   * add (R2). Affects only what this side sends: the sender picks reliability and channel.
   */
  @Synchronized
  fun apply() {
    resolve()
    if (undo.isNotEmpty()) return
    listOf(Kind.ITEM_FIELDS, Kind.ITEM_MOD_DATA, Kind.WEAPON_FIELDS, Kind.ITEM_STATS).forEach { k ->
      val t = k.type
      val reliability = t.packetReliability
      val channel = t.orderingChannel
      undo += {
        t.packetReliability = reliability
        t.orderingChannel = channel
      }
      t.packetReliability = Protocol.RELIABLE_ORDERED
      t.orderingChannel = Protocol.SHARED_CHANNEL
    }
  }

  /** Puts back what [apply] found; nothing when it did not run. */
  @Synchronized
  fun restore() {
    undo.forEach { it() }
    undo.clear()
  }
}
