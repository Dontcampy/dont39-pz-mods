package com.pzlauncher.zabric.mods.impl;

import java.util.List;

/** Like {@link FolderListHost}, for a launch without a request. */
public class UntouchedHost {
    public void getInstalledItemModsFolders(List<String> folders) {
        folders.add("/content/111/mods");
    }
}
