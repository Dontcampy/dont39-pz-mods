package com.pzlauncher.zabric.mods.impl.weave;

import me.zed_0xff.zombie_buddy.Patch;

import java.util.List;

/** A ZombieBuddy patch on the stand-in, applied after Zabric's hook as a mod's hook on the game's class would be. */
public final class LaterPatch {
    public static volatile List<String> seen;

    private LaterPatch() {
    }

    @Patch(className = "com.pzlauncher.zabric.mods.impl.FolderListHost", methodName = "getInstalledItemModsFolders")
    public static final class Exit {
        private Exit() {
        }

        @Patch.OnExit
        public static void exit(@Patch.Argument(0) List<String> folders) {
            seen = List.copyOf(folders);
        }
    }
}
