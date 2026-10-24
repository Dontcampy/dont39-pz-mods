package com.dont39.pz.syncguard.client.hooks;

import com.dont39.pz.syncguard.client.ClientSync;
import com.dont39.pz.syncguard.game.Gate;
import com.pzlauncher.zabric.hook.At;
import com.pzlauncher.zabric.hook.CallbackInfo;
import com.pzlauncher.zabric.hook.Hooks;
import com.pzlauncher.zabric.hook.Inject;
import com.pzlauncher.zabric.hook.Self;
import zombie.core.network.ByteBufferReader;
import zombie.network.PacketTypes.PacketType;

/**
 * {@code -Dsyncguard.debugDropDelta} and {@code -Dsyncguard.debugDropModule} (off by default): every in-game packet
 * the client handles passes here before the game parses it, the one place all eight in-scope types share
 * (SyncItemModData applies in its parse), server commands included. Cancels only a packet a switch drops.
 */
@Hooks(value = PacketType.class, gate = Gate.class)
public final class DropHooks {
    private DropHooks() {
    }

    @Inject(method = "onClientPacket", at = @At("HEAD"), cancellable = true)
    public static void received(@Self PacketType type, ByteBufferReader in, CallbackInfo ci) {
        if (ClientSync.dropDelta(type, in)) {
            ci.cancel();
        }
    }
}
