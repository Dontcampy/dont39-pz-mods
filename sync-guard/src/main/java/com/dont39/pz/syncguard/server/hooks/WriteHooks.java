package com.dont39.pz.syncguard.server.hooks;

import com.dont39.pz.syncguard.game.Gate;
import com.dont39.pz.syncguard.server.ServerSync;
import com.pzlauncher.zabric.hook.At;
import com.pzlauncher.zabric.hook.CallbackInfo;
import com.pzlauncher.zabric.hook.Hooks;
import com.pzlauncher.zabric.hook.Inject;
import com.pzlauncher.zabric.hook.Self;
import zombie.core.network.ByteBufferWriter;
import zombie.network.packets.AddInventoryItemToContainerPacket;
import zombie.network.packets.INetworkPacket;
import zombie.network.packets.ItemStatsPacket;
import zombie.network.packets.RemoveInventoryItemFromContainerPacket;
import zombie.network.packets.ReplaceInventoryItemInContainerPacket;
import zombie.network.packets.SyncHandWeaponFieldsPacket;
import zombie.network.packets.SyncItemFieldsPacket;
import zombie.network.packets.SyncItemModDataPacket;

/**
 * The in-scope packets as they are written: the send that follows names the connection, and only the packet knows
 * its target. SyncItemDelete inherits RemoveInventoryItemFromContainer's write.
 */
@Hooks(value = {
    AddInventoryItemToContainerPacket.class,
    RemoveInventoryItemFromContainerPacket.class,
    ReplaceInventoryItemInContainerPacket.class,
    SyncItemFieldsPacket.class,
    SyncItemModDataPacket.class,
    SyncHandWeaponFieldsPacket.class,
    ItemStatsPacket.class,
}, gate = Gate.class)
public final class WriteHooks {
    private WriteHooks() {
    }

    @Inject(method = "write", at = @At("RETURN"))
    public static void written(@Self INetworkPacket packet, ByteBufferWriter out, CallbackInfo ci) {
        ServerSync.written(packet);
    }
}
