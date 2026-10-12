package com.dont39.pz.zombiebuddykotlin;

/**
 * Runs before the Kotlin runtime is installed: Java only, no reference to any kotlin.* type (checkBootstrapClasses).
 */
public class Main {
    public static void main(String[] args) {
        String version = Main.class.getPackage().getImplementationVersion();
        ZombieBuddyKotlin.log((version != null ? version : "dev") + " loaded");
        ZombieBuddyKotlin.install();
    }
}
