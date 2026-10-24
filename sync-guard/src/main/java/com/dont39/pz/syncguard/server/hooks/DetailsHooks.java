package com.dont39.pz.syncguard.server.hooks;

import com.dont39.pz.syncguard.game.Gate;
import com.dont39.pz.syncguard.server.ServerSync;
import com.pzlauncher.zabric.hook.At;
import com.pzlauncher.zabric.hook.CallbackInfo;
import com.pzlauncher.zabric.hook.Hooks;
import com.pzlauncher.zabric.hook.Inject;
import zombie.core.network.ByteBufferWriter;
import zombie.core.raknet.UdpConnection;
import zombie.network.ConnectionDetails;
import zombie.network.ServerWorldDatabase;

/** The connection-details a joining client gets: the trailer that advertises the mod (R8). */
@Hooks(value = ConnectionDetails.class, gate = Gate.class)
public final class DetailsHooks {
    private DetailsHooks() {
    }

    @Inject(method = "write", at = @At("RETURN"))
    public static void written(UdpConnection connection, ServerWorldDatabase.LogonResult logon, ByteBufferWriter out,
            CallbackInfo ci) {
        ServerSync.detailsWritten(out);
    }
}
