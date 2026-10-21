package com.pzlauncher.zabric;

import com.pzlauncher.zabric.entrypoint.Lifecycle;
import com.pzlauncher.zabric.mods.impl.LaunchOverrides;
import com.pzlauncher.zabric.mods.impl.WidenRootsFile;
import com.pzlauncher.zabric.widener.AccessWidener;

/** Called by ZombieBuddy when the mod list loads, whether or not the mod was preloaded. */
public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        AccessWidener.report();
        WidenRootsFile.report();
        LaunchOverrides.report();
        Lifecycle.onZabricMain(PreMain.ran());
    }
}
