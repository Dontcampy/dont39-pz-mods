package com.dont39.pz.zbkt;

import zombie.debug.DebugType;

/**
 * Runs before the Kotlin runtime is installed: Java only, no reference to any kotlin.* type (checkBootstrapClasses).
 */
public class Main {
    public static void main(String[] args) {
        String version = Main.class.getPackage().getImplementationVersion();
        DebugType.Mod.println(ZombieBuddyKotlin.PREFIX + "%s loaded", version != null ? version : "dev");
        ZombieBuddyKotlin.install();
    }
}
