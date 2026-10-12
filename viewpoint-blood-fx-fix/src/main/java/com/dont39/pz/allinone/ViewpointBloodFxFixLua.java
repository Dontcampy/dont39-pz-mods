package com.dont39.pz.allinone;

import com.dont39.pz.viewpointbloodfxfix.ViewpointBloodFxFix;
import se.krka.kahlua.integration.annotations.LuaMethod;

// ZombieBuddy only exposes global Lua functions from classes in exactly javaPkgName.
public final class ViewpointBloodFxFixLua {
    private ViewpointBloodFxFixLua() {
    }

    @LuaMethod(name = "Dont39AllInOne_setViewpointBloodFxFixForce", global = true)
    public static void setForce(boolean force) {
        ViewpointBloodFxFix.setForce(force);
    }
}
