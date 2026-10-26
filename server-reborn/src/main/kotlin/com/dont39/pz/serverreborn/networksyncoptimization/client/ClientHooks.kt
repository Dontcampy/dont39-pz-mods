package com.dont39.pz.serverreborn.networksyncoptimization.client

import com.dont39.pz.serverreborn.ServerReborn
import com.pzlauncher.zabric.hook.At
import com.pzlauncher.zabric.hook.CallbackInfo
import com.pzlauncher.zabric.hook.Hooks
import com.pzlauncher.zabric.hook.Inject
import com.pzlauncher.zabric.hook.Self
import zombie.characters.IsoPlayer
import zombie.core.network.ByteBufferReader
import zombie.core.raknet.UdpConnection
import zombie.gameStates.IngameState
import zombie.inventory.ItemContainer
import zombie.network.GameClient
import zombie.network.GameServer
import zombie.network.PacketTypes.PacketType
import zombie.network.ZomboidNetData

/** The client's queued packets, its frame and its PlayerConnect. */
@Hooks(GameClient::class, gate = ServerReborn::class)
internal object ClientNetHooks {
  @Inject(method = ["mainLoopDealWithNetData"], at = [At("HEAD")])
  fun dispatch(data: ZomboidNetData?, ci: CallbackInfo) = ClientSync.dispatch(data!!)

  // every frame; also while shut, to say a Bye a failure left pending
  @Inject(method = ["update"], at = [At("RETURN")], always = true)
  fun frame(ci: CallbackInfo) = ClientSync.tick()

  @Inject(method = ["sendPlayerConnect"], at = [At("RETURN")])
  fun playerConnectSent(player: IsoPlayer?, ci: CallbackInfo) = ClientSync.playerConnectSent()
}

/** A container the player opens for the first time on this client (R7). */
@Hooks(ItemContainer::class, gate = ServerReborn::class)
internal object ContainerHooks {
  // the game's Lua calls it only for a container this client has not explored, before the game fills it
  @Inject(method = ["requestServerItemsForContainer"], at = [At("HEAD")])
  fun itemsRequested(@Self container: ItemContainer, ci: CallbackInfo) {
    if (!GameServer.server) {
      ClientSync.itemsRequested(container)
    }
  }
}

/**
 * `-Dserverreborn.debugDropDelta` and `-Dserverreborn.debugDropModule` (off by default): every in-game packet the
 * client handles passes here before the game parses it, the one place all eight in-scope types share (SyncItemModData
 * applies in its parse), server commands included. Cancels only a packet a switch drops.
 */
@Hooks(PacketType::class, gate = ServerReborn::class)
internal object DropHooks {
  @Inject(method = ["onClientPacket"], at = [At("HEAD")], cancellable = true)
  fun received(@Self type: PacketType, input: ByteBufferReader?, ci: CallbackInfo) {
    if (ClientSync.dropDelta(type, input)) {
      ci.cancel()
    }
  }
}

/** Leaving a game, where a server's session ends. */
@Hooks(IngameState::class, gate = ServerReborn::class)
internal object LeaveHooks {
  @Inject(method = ["exit"], at = [At("HEAD")])
  fun leave(ci: CallbackInfo) {
    if (!GameServer.server) {
      ClientSync.leave()
    }
  }
}

/**
 * The client's received packets as GameClient.addIncoming reads them for its queue. On the server the mod's id never
 * gets here: GameServer.addIncoming is cancelled for it first.
 */
@Hooks(ZomboidNetData::class, gate = ServerReborn::class)
internal object NetDataHooks {
  // Network thread. Also after a failure, until the server has the Bye: read would log the mod's id as unknown.
  @Inject(method = ["read"], at = [At("HEAD")], cancellable = true, always = true)
  fun read(
    @Self data: ZomboidNetData,
    id: Short,
    input: ByteBufferReader?,
    connection: UdpConnection?,
    ci: CallbackInfo,
  ) {
    if (ClientSync.incoming(data, id, input, connection)) {
      ci.cancel()
    }
  }
}
