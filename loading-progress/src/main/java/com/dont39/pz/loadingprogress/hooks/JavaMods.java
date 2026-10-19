package com.dont39.pz.loadingprogress.hooks;

import com.pzlauncher.zabric.entrypoint.LoaderProgress;

/** ZombieBuddy's Java mod jars, through Zabric. Registered at preLaunch, so it names no game type. */
public final class JavaMods implements LoaderProgress.Listener {
    @Override
    public void jarLoading(int index, int total, String modId) {
        Loading.javaMod(index, total, modId, false);
    }

    @Override
    public void jarLoaded(int index, int total, String modId) {
        Loading.javaMod(index, total, modId, true);
    }
}
