package com.pzlauncher.zabric.hook.check;

/** Loaded before its glue is woven, like a game class ZombieBuddy has to retransform. */
public class Loaded {
    public int value(int x) {
        return x + 1;
    }
}
