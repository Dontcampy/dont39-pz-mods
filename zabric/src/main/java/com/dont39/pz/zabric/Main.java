package com.dont39.pz.zabric;

import com.dont39.pz.zabric.entrypoint.Lifecycle;
import com.dont39.pz.zabric.widener.AccessWidener;

/** Called by ZombieBuddy when the mod list loads, whether or not the mod was preloaded. */
public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        AccessWidener.report();
        Lifecycle.onZabricMain(PreMain.ran());
    }
}
