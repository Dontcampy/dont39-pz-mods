package com.dont39.pz.serverreborn.networksyncoptimization.game

import zombie.inventory.ItemContainer
import zombie.iso.IsoGridSquare
import zombie.network.fields.ContainerID
import zombie.network.packets.AddInventoryItemToContainerPacket
import zombie.network.packets.INetworkPacket
import zombie.network.packets.ItemStatsPacket
import zombie.network.packets.SyncHandWeaponFieldsPacket
import zombie.network.packets.SyncItemFieldsPacket
import zombie.network.packets.SyncItemModDataPacket

/**
 * The container of a snapshot's game payloads for a nested bag, which no game id addresses: the packet classes set,
 * write, read and look up their container through it. On the server it stays Undefined, one byte that the game reads
 * without a lookup; on the client it stands for the bag found from the snapshot's own address.
 */
internal class PinnedContainerID private constructor(private val pinned: ItemContainer) : ContainerID() {
  // ContainerID.set throws for some nested bags (an outer bag on the floor has no parent object)
  override fun set(container: ItemContainer?) {}

  override fun setFloor(container: ItemContainer?, square: IsoGridSquare?) {}

  override fun findObject() {}

  override fun getContainer(): ItemContainer = pinned

  companion object {
    /** Points [packet]'s container at [container]; before setData on the server, parse on a client. */
    fun <P : INetworkPacket> pin(packet: P, container: ItemContainer): P {
      val id = PinnedContainerID(container)
      when (packet) {
        is AddInventoryItemToContainerPacket -> packet.containerId = id
        is SyncItemFieldsPacket -> packet.containerId = id
        is SyncItemModDataPacket -> packet.containerId = id
        is ItemStatsPacket -> packet.containerId = id
        is SyncHandWeaponFieldsPacket -> packet.containerId = id
        else -> throw IllegalArgumentException("a ${packet.javaClass.name} is not a snapshot payload")
      }
      return packet
    }

    /** [pin] when [container] is the nested bag the payloads are for; [packet] as it is for null. */
    fun <P : INetworkPacket> pinned(packet: P, container: ItemContainer?): P =
      if (container == null) packet else pin(packet, container)
  }
}
