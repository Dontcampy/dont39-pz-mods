package com.pzlauncher.zabric.hook.check;

/** Loaded before preLaunch, like a game class the loader loads at premain. */
public class PreLoaded {
    public int value(int x) {
        return x + 1;
    }
}
