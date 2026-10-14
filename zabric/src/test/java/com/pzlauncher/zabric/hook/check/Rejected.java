package com.pzlauncher.zabric.hook.check;

/** Hooked by advice ByteBuddy refuses to apply. */
public class Rejected {
    public String name() {
        return "rejected";
    }
}
