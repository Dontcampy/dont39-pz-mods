package com.pzlauncher.zabric.hook.weave;

/** What the weave test hooks; loaded only after the glue is applied. */
public class Target {
    public int compute(int x) {
        Events.add("compute body " + x);
        return x * 2;
    }

    public String greet(String name) {
        Events.add("greet body " + name);
        return "hello " + name;
    }

    public void ping() {
        Events.add("ping body");
    }

    public void boom() {
        Events.add("boom body");
        throw new IllegalStateException("boom");
    }

    public int fail(int x) {
        Events.add("fail body");
        return x + 100;
    }

    public void run(Runnable body) {
        body.run();
    }
}
