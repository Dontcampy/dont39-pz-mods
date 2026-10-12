package com.dont39.pz.allinone;

import com.dont39.pz.viewpointbloodfxfix.ViewpointBloodFxFix;
import zombie.debug.DebugType;

public class Main {
    public static void main(String[] args) {
        String version = Main.class.getPackage().getImplementationVersion();
        DebugType.Mod.println("[Dont39AllInOne] " + (version != null ? version : "dev") + " loaded");
        ViewpointBloodFxFix.init();
    }
}
