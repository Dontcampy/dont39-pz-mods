package com.dont39.pz.zbkt;

import zombie.debug.DebugType;
import zombie.debug.LogSeverity;

/**
 * Called by ZombieBuddy when the mod list loads. Java only, no reference to any kotlin.* type: on a launch without the
 * swap the game's old Kotlin is all there is (checkBootstrapClasses).
 */
public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        String version = Main.class.getPackage().getImplementationVersion();
        DebugType.Mod.println(ZombieBuddyKotlin.PREFIX + "%s loaded", version != null ? version : "dev");
        try {
            PreloadRequest.request("ZombieBuddyKotlin", PreMain.ran());
        } catch (Throwable t) {
            DebugType.Mod.printException(t, ZombieBuddyKotlin.PREFIX + "cannot ask Zabric to register it as a"
                    + " ZombieBuddy preload mod", LogSeverity.Error);
        }
        ZombieBuddyKotlin.report();
    }
}
