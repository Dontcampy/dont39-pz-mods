package com.dont39.pz.zabric;

import com.dont39.pz.zabric.setup.Registration;
import se.krka.kahlua.integration.annotations.LuaMethod;

/** For the client Lua's restart dialog. ZombieBuddy exposes global Lua functions only from exactly javaPkgName. */
public final class RestartNotice {
    private RestartNotice() {
    }

    @LuaMethod(name = "Zabric_restartNeeded", global = true)
    public static boolean restartNeeded() {
        return Registration.restartNeeded();
    }
}
