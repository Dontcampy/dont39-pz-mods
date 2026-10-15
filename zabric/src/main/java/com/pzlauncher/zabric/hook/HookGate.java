package com.pzlauncher.zabric.hook;

/**
 * Decides whether a hook class's handlers run and hears about their failures. Its class initializer must not fail: it
 * runs inside the hooked method.
 */
public interface HookGate {
    /** Checked on every call of a hooked method, on any thread: keep it cheap and thread-safe. */
    boolean active();

    /**
     * A handler threw, or the loader did not weave a hook class into a target; {@code what} names which. May be called
     * again after the gate turned inactive. Must not throw.
     */
    void failed(String what, Throwable t);
}
