package com.dont39.pz.zabric;

import com.dont39.pz.zabric.preload.Preload;
import com.dont39.pz.zabric.widener.AccessWidener;

/** Called by ZombieBuddy when the mod list loads, whether or not the mod was preloaded. */
public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        Preload.register("Zabric", PreMain.ran());
        Preload.registerRequested();
        AccessWidener.report();
    }
}
