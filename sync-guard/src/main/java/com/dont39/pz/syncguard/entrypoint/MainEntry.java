package com.dont39.pz.syncguard.entrypoint;

import com.dont39.pz.syncguard.LuaGlobals;
import com.dont39.pz.syncguard.client.ClientSync;
import com.dont39.pz.syncguard.game.Containers;
import com.dont39.pz.syncguard.game.Gate;
import com.dont39.pz.syncguard.game.PacketScope;
import com.dont39.pz.syncguard.game.Settings;
import com.dont39.pz.syncguard.server.ServerSync;
import com.pzlauncher.zabric.entrypoint.MainEntrypoint;
import com.pzlauncher.zabric.widener.AccessWidener;
import zombie.debug.DebugType;
import zombie.debug.LogSeverity;
import zombie.network.GameServer;

import static com.dont39.pz.syncguard.Mod.MOD_ID;
import static com.dont39.pz.syncguard.Mod.PREFIX;

/** Decides, once the mod list has loaded, whether the mod's hooks open, and reports it. */
public final class MainEntry implements MainEntrypoint {
    @Override
    public void onMain() {
        DebugType.Mod.println(PREFIX + "%s loaded", MainEntry.class.getPackage().getImplementationVersion());
        Settings.load();
        if (AccessWidener.report(MOD_ID, MainEntry.class) != null) {
            DebugType.Mod.warn(PREFIX + "idle: the access widener did not apply the mod's list (line above)");
            return;
        }
        String problem = Gate.targetProblem();
        if (problem != null) {
            DebugType.Mod.error(PREFIX + "off: %s", problem);
            return;
        }
        try {
            PacketScope.resolve();
            Containers.check();
        } catch (IllegalStateException e) {
            DebugType.Mod.printException(e, PREFIX + "off: the game's packets differ from the ones this version of"
                    + " the mod checks", LogSeverity.Error);
            return;
        }
        if (!GameServer.server) {
            try {
                LuaGlobals.exposeIfMissing();
            } catch (RuntimeException e) {
                DebugType.Mod.printException(e, PREFIX + "off: its Lua functions could not be added", LogSeverity.Error);
                return;
            }
            Gate.INSTANCE.open(ClientSync::stop);
            DebugType.Mod.println(PREFIX + "ready on a client; it turns on for a server that runs it too");
            Settings.reportClientSwitches();
        } else if (GameServer.coop) {
            DebugType.Mod.println(PREFIX + "off on a co-op host: it runs on dedicated servers only");
        } else {
            ServerSync.start();
            Gate.INSTANCE.open(ServerSync::stop);
            DebugType.Mod.println(PREFIX + "active on a dedicated server: joining clients that run it are checked");
        }
    }
}
