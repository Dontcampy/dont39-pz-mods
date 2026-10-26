package com.dont39.pz.serverreborn.networksyncoptimization.server

import com.dont39.pz.serverreborn.networksyncoptimization.protocol.ContainerKey
import com.dont39.pz.serverreborn.networksyncoptimization.protocol.Key
import zombie.core.raknet.UdpConnection
import zombie.vehicles.VehicleManager

/** Which objects the game still sends a connection the changes of (R1). Main thread only. */
internal object Relevance {
  fun relevant(c: UdpConnection, key: Key): Boolean {
    val k = key.container
    return when (k.kind) {
      ContainerKey.Kind.PLAYER, ContainerKey.Kind.BAG -> c.hasPlayer(k.owner.toShort())
      ContainerKey.Kind.VEHICLE, ContainerKey.Kind.VEHICLE_BAG -> {
        val v = VehicleManager.instance.getVehicleByID(k.owner.toShort())
        v != null && c.isRelevantTo(v.x, v.y)
      }
      ContainerKey.Kind.OBJECT, ContainerKey.Kind.OBJECT_CONTAINER, ContainerKey.Kind.CORPSE,
      ContainerKey.Kind.WORLD_ITEM,
      -> c.isRelevantTo(k.x.toFloat(), k.y.toFloat())
      // never only its owner's: an anchor is not a player's
      ContainerKey.Kind.NESTED -> relevant(c, k.anchor!!)
    }
  }
}
