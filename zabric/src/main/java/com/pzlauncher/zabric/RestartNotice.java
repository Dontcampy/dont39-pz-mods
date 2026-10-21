package com.pzlauncher.zabric;

import com.pzlauncher.zabric.internal.GameFolders;
import com.pzlauncher.zabric.relaunch.impl.RelaunchFailure;
import com.pzlauncher.zabric.restart.RestartList;
import se.krka.kahlua.integration.annotations.LuaMethod;
import se.krka.kahlua.vm.KahluaTable;
import zombie.Lua.LuaManager;

import java.util.List;

/** For the client Lua's restart dialogs. ZombieBuddy exposes global Lua functions only from exactly javaPkgName. */
public final class RestartNotice {
    private RestartNotice() {
    }

    /** The display names of the mods that need a restart to take effect, as a Lua sequence. */
    @LuaMethod(name = "Zabric_restartMods", global = true)
    public static KahluaTable restartMods() {
        List<String> names = RestartList.names();
        KahluaTable table = LuaManager.platform.newTable();
        for (int i = 0; i < names.size(); i++) {
            table.rawset(i + 1, names.get(i));
        }
        return table;
    }

    /**
     * Why the game could not start again after it quit to restart itself ({@code Relaunch}), once: null when that did
     * not happen or was already shown.
     */
    @LuaMethod(name = "Zabric_relaunchFailure", global = true)
    public static String relaunchFailure() {
        return RelaunchFailure.take(GameFolders.zabricDir());
    }
}
