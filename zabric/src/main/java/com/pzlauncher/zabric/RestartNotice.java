package com.pzlauncher.zabric;

import com.pzlauncher.zabric.restart.RestartList;
import se.krka.kahlua.integration.annotations.LuaMethod;
import se.krka.kahlua.vm.KahluaTable;
import zombie.Lua.LuaManager;

import java.util.List;

/** For the client Lua's restart dialog. ZombieBuddy exposes global Lua functions only from exactly javaPkgName. */
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
}
