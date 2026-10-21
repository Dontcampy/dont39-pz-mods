package com.pzlauncher.zabric.mods.impl;

import java.util.List;

/** Stands in for the game's ZomboidFileSystem: adds each installed item's mods folder. */
public class FolderListHost {
    private final List<String> installed;

    public FolderListHost(List<String> installed) {
        this.installed = installed;
    }

    public void getInstalledItemModsFolders(List<String> folders) {
        folders.addAll(installed);
    }
}
