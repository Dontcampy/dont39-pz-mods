package com.dont39.pz.serverreborn.networksyncoptimization.client

import com.dont39.pz.serverreborn.networksyncoptimization.game.Containers
import com.dont39.pz.serverreborn.networksyncoptimization.protocol.ContainerKey
import com.dont39.pz.serverreborn.networksyncoptimization.protocol.WireKey
import zombie.core.raknet.UdpConnection
import zombie.inventory.ItemContainer
import zombie.vehicles.VehiclePart

/** The client's container for a key the server sent, through the game's own lookup. Main thread only. */
internal object Lookup {
  /**
   * [container]: the client's, or null when it has none addressed under that key (not loaded yet, a parent or bag that
   * is gone, or a stand-in the game's lookup made up for an index out of range). [key]: null for a floor container,
   * which the server never sends. [part]: the vehicle part whose items the container holds.
   */
  class Found(val key: ContainerKey?, val container: ItemContainer?, val part: VehiclePart?) {
    fun name(wire: WireKey): String {
      val where = key?.toString() ?: "a floor container"
      return if (wire.isItem) "item ${wire.itemId} in $where" else where
    }
  }

  fun find(wire: WireKey, connection: UdpConnection?): Found {
    val key = Containers.keyOf(wire.containerBytes)
    val at = Containers.find(wire.containerBytes, connection) ?: return Found(key, null, null)
    val container = at.container.takeIf { key != null && Containers.live(it, key) }
    return Found(key, container, at.part)
  }
}
