package com.dont39.pz.zabric;

import com.dont39.pz.zabric.setup.Registration;

/** Called by ZombieBuddy when the mod list loads, whether or not the mod was preloaded. */
public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        Registration.run(PreMain.ran());
    }
}
