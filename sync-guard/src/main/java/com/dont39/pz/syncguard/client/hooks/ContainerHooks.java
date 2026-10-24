package com.dont39.pz.syncguard.client.hooks;

import com.dont39.pz.syncguard.client.ClientSync;
import com.dont39.pz.syncguard.game.Gate;
import com.pzlauncher.zabric.hook.At;
import com.pzlauncher.zabric.hook.CallbackInfo;
import com.pzlauncher.zabric.hook.Hooks;
import com.pzlauncher.zabric.hook.Inject;
import com.pzlauncher.zabric.hook.Self;
import zombie.inventory.ItemContainer;
import zombie.network.GameServer;

/** A container the player opens for the first time on this client (R7). */
@Hooks(value = ItemContainer.class, gate = Gate.class)
public final class ContainerHooks {
    private ContainerHooks() {
    }

    // the game's Lua calls it only for a container this client has not explored, before the game fills it
    @Inject(method = "requestServerItemsForContainer", at = @At("HEAD"))
    public static void itemsRequested(@Self ItemContainer container, CallbackInfo ci) {
        if (!GameServer.server) {
            ClientSync.itemsRequested(container);
        }
    }
}
