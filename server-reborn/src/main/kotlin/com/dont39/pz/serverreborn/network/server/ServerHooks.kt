package com.dont39.pz.serverreborn.network.server

import com.dont39.pz.serverreborn.ServerReborn
import com.pzlauncher.zabric.hook.At
import com.pzlauncher.zabric.hook.CallbackInfo
import com.pzlauncher.zabric.hook.CallbackInfoReturnable
import com.pzlauncher.zabric.hook.Hooks
import com.pzlauncher.zabric.hook.Inject
import com.pzlauncher.zabric.hook.LocalRef
import com.pzlauncher.zabric.hook.Self
import com.pzlauncher.zabric.hook.Share
import com.pzlauncher.zabric.hook.Thrown
import zombie.core.network.ByteBufferReader
import zombie.core.network.ByteBufferWriter
import zombie.core.raknet.UdpConnection
import zombie.inventory.InventoryItem
import zombie.inventory.ItemContainer
import zombie.network.ConnectionDetails
import zombie.network.GameServer
import zombie.network.IConnection
import zombie.network.PacketTypes.PacketType
import zombie.network.ServerWorldDatabase
import zombie.network.packets.AddInventoryItemToContainerPacket
import zombie.network.packets.INetworkPacket
import zombie.network.packets.ItemStatsPacket
import zombie.network.packets.RemoveInventoryItemFromContainerPacket
import zombie.network.packets.ReplaceInventoryItemInContainerPacket
import zombie.network.packets.SyncHandWeaponFieldsPacket
import zombie.network.packets.SyncItemFieldsPacket
import zombie.network.packets.SyncItemModDataPacket

/**
 * The ItemContainer methods that add items to or remove them from its list themselves; the others (DoAddItem, AddItems
 * of items or of a type, addItem(ItemKey), SpawnItem of an item, removeItemWithID, takeItemsFrom, ...) call one of
 * these. A change by any route counts, as a mod may sync it its own way (D7). Not seen: code that edits the public item
 * list directly.
 */
@Hooks(ItemContainer::class, gate = ServerReborn::class)
internal object ContentHooks {
  // also takes the item out of its old container through Remove
  @Inject(method = ["AddItem(Lzombie/inventory/InventoryItem;)Lzombie/inventory/InventoryItem;"], at = [At("HEAD")])
  fun addItem(@Self container: ItemContainer, ci: CallbackInfoReturnable<InventoryItem>) =
    Touches.contentChanged(container)

  @Inject(
    method = ["AddItemBlind(Lzombie/inventory/InventoryItem;)Lzombie/inventory/InventoryItem;"],
    at = [At("HEAD")],
  )
  fun addItemBlind(@Self container: ItemContainer, ci: CallbackInfoReturnable<InventoryItem>) =
    Touches.contentChanged(container)

  // Lua's container:AddItem("Base.X")
  @Inject(method = ["AddItem(Ljava/lang/String;)Lzombie/inventory/InventoryItem;"], at = [At("HEAD")])
  fun addItemOfType(@Self container: ItemContainer, ci: CallbackInfoReturnable<InventoryItem>) =
    Touches.contentChanged(container)

  @Inject(method = ["AddItem(Ljava/lang/String;F)Z"], at = [At("HEAD")])
  fun addItemWithUses(@Self container: ItemContainer, ci: CallbackInfoReturnable<Boolean>) =
    Touches.contentChanged(container)

  @Inject(method = ["AddItem(Ljava/lang/String;FZ)Z"], at = [At("HEAD")])
  fun addItemWithUsesSynced(@Self container: ItemContainer, ci: CallbackInfoReturnable<Boolean>) =
    Touches.contentChanged(container)

  @Inject(method = ["Remove(Lzombie/inventory/InventoryItem;)V"], at = [At("HEAD")])
  fun remove(@Self container: ItemContainer, ci: CallbackInfo) = Touches.contentChanged(container)

  @Inject(method = ["DoRemoveItem(Lzombie/inventory/InventoryItem;)V"], at = [At("HEAD")])
  fun doRemoveItem(@Self container: ItemContainer, ci: CallbackInfo) = Touches.contentChanged(container)

  @Inject(method = ["Remove(Ljava/lang/String;)V"], at = [At("HEAD")])
  fun removeOfType(@Self container: ItemContainer, ci: CallbackInfo) = Touches.contentChanged(container)

  @Inject(method = ["Remove(Lzombie/scripting/objects/ItemType;)Lzombie/inventory/InventoryItem;"], at = [At("HEAD")])
  fun removeOfItemType(@Self container: ItemContainer, ci: CallbackInfoReturnable<InventoryItem>) =
    Touches.contentChanged(container)

  @Inject(method = ["RemoveAll(Ljava/lang/String;I)Ljava/util/ArrayList;"], at = [At("HEAD")])
  fun removeAll(@Self container: ItemContainer, ci: CallbackInfoReturnable<java.util.ArrayList<InventoryItem>>) =
    Touches.contentChanged(container)

  @Inject(method = ["RemoveOneOf(Ljava/lang/String;Z)Lzombie/inventory/InventoryItem;"], at = [At("HEAD")])
  fun removeOneOf(@Self container: ItemContainer, ci: CallbackInfoReturnable<InventoryItem>) =
    Touches.contentChanged(container)

  @Inject(method = ["clear()V"], at = [At("HEAD")])
  fun clear(@Self container: ItemContainer, ci: CallbackInfo) = Touches.contentChanged(container)

  @Inject(method = ["removeAllItems()V"], at = [At("HEAD")])
  fun removeAllItems(@Self container: ItemContainer, ci: CallbackInfo) = Touches.contentChanged(container)

  @Inject(method = ["setItems(Ljava/util/ArrayList;)V"], at = [At("HEAD")])
  fun setItems(@Self container: ItemContainer, ci: CallbackInfo) = Touches.contentChanged(container)

  @Inject(method = ["emptyIt()V"], at = [At("HEAD")])
  fun emptyIt(@Self container: ItemContainer, ci: CallbackInfo) = Touches.contentChanged(container)
}

/** The connection-details a joining client gets: the trailer that advertises the mod (R8). */
@Hooks(ConnectionDetails::class, gate = ServerReborn::class)
internal object DetailsHooks {
  @Inject(method = ["write"], at = [At("RETURN")])
  fun written(
    connection: UdpConnection?,
    logon: ServerWorldDatabase.LogonResult?,
    out: ByteBufferWriter?,
    ci: CallbackInfo,
  ) = ServerSync.detailsWritten(out)
}

/** The server's incoming packets and connections. */
@Hooks(GameServer::class, gate = ServerReborn::class)
internal object NetHooks {
  // Network thread. Cancels only the mod's own id: the game would log it as unknown and run its packet anticheat, which
  // can kick or ban. Also after a failure, as clients still send until they learn of it.
  @Inject(method = ["addIncoming"], at = [At("HEAD")], cancellable = true, always = true)
  fun incoming(id: Short, input: ByteBufferReader?, connection: UdpConnection?, ci: CallbackInfo) {
    if (ServerSync.incoming(id, input, connection)) {
      ci.cancel()
    }
  }

  @Inject(method = ["disconnect"], at = [At("HEAD")])
  fun disconnect(connection: UdpConnection?, reason: String?, ci: CallbackInfo) = ServerSync.disconnected(connection)
}

/** Where every packet is sent and every client packet is handled on the server: touch recording (R3). */
@Hooks(PacketType::class, gate = ServerReborn::class)
internal object PacketTypeHooks {
  // every packet on both sides: an early return unless an in-scope packet was just written
  @Inject(method = ["send"], at = [At("HEAD")])
  fun send(@Self type: PacketType, connection: IConnection?, ci: CallbackInfo) = Touches.sending(type, connection)

  @Inject(method = ["onServerPacket"], at = [At("HEAD")])
  fun receiving(
    @Self type: PacketType,
    input: ByteBufferReader?,
    connection: UdpConnection?,
    ci: CallbackInfo,
    @Share("start") start: LocalRef<Int>,
  ) = start.set(Touches.receiving(type, input))

  // FINALLY: rejected and failed packets count as touched too
  @Inject(method = ["onServerPacket"], at = [At("FINALLY")])
  fun received(
    @Self type: PacketType,
    input: ByteBufferReader?,
    connection: UdpConnection?,
    ci: CallbackInfo,
    @Thrown thrown: Throwable?,
    @Share("start") start: LocalRef<Int>,
  ) {
    val at: Int? = start.get()
    if (at != null) {
      Touches.received(type, input, connection, at)
    }
  }
}

/**
 * The in-scope packets as they are written: the send that follows names the connection, and only the packet knows its
 * target. SyncItemDelete inherits RemoveInventoryItemFromContainer's write.
 */
@Hooks(
  AddInventoryItemToContainerPacket::class,
  RemoveInventoryItemFromContainerPacket::class,
  ReplaceInventoryItemInContainerPacket::class,
  SyncItemFieldsPacket::class,
  SyncItemModDataPacket::class,
  SyncHandWeaponFieldsPacket::class,
  ItemStatsPacket::class,
  gate = ServerReborn::class,
)
internal object WriteHooks {
  @Inject(method = ["write"], at = [At("RETURN")])
  fun written(@Self packet: INetworkPacket, out: ByteBufferWriter?, ci: CallbackInfo) = Touches.written(packet)
}
