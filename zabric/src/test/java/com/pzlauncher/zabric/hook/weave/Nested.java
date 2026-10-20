package com.pzlauncher.zabric.hook.weave;

/**
 * A third weave target, shaped like a widened game class: a nested class reached by binary name, whose fields and type
 * are public as a widening list makes them, while its method keeps package access (lists do not widen methods); and a
 * private step behind a public method.
 */
public final class Nested {
    public final Item item = new Item();

    public void update() {
        step();
    }

    private void step() {
        Events.add("step body");
    }

    public static final class Item {
        public String error;
        public State state = State.CHECK;

        void setState(State next) {
            Events.add("setState body " + next);
            state = next;
        }
    }

    public enum State { CHECK, READY, FAIL }
}
