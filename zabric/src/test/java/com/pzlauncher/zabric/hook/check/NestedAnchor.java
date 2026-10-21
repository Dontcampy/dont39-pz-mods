package com.pzlauncher.zabric.hook.check;

/**
 * Loaded before its glue is woven: like a game class the loader loaded at premain, it makes the loader weave the glue's
 * package with advice that can be retransformed, as it does for the game.
 */
public class NestedAnchor {
    public int value(int x) {
        return x + 1;
    }
}
