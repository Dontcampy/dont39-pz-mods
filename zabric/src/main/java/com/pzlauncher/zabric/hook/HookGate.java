package com.pzlauncher.zabric.hook;

/**
 * Decides whether a hook class's handlers run, and hears about their failures. Named in {@link Hooks#gate()}; without
 * one, a hook class uses its mod's {@link ModHooks#defaultGate()}. Its {@code INSTANCE} is read by the glue inside the
 * hooked methods, so the gate's class initializer does nothing that can fail.
 */
public interface HookGate {
    /**
     * Checked before every call of a hooked method, on whatever thread calls it: keep it cheap and thread-safe. False:
     * the handlers of that call do not run, except {@link Inject#always()} ones. What it throws is passed to
     * {@link #failed} and counts as false.
     */
    boolean active();

    /**
     * Called with everything a handler throws; the hooked method then goes on as without the handler. {@code what}
     * names the handler and the target, e.g. {@code "running MyHooks.onRender on zombie.ui.UITextBox2.render"}. Also
     * called when the loader did not weave a hook class into a target, e.g.
     * {@code "weaving MyHooks into zombie.ui.UITextBox2.render"}: its handlers there never run. A gate
     * usually turns itself inactive and logs. Called again for later failures, e.g. of a handler that undoes; it never
     * throws.
     */
    void failed(String what, Throwable t);
}
