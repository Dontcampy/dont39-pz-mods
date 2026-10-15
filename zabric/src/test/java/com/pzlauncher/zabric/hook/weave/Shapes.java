package com.pzlauncher.zabric.hook.weave;

/** A second weave target, for the shapes {@link Target} lacks: a constructor, an array, a long, a non-void throw. */
public class Shapes {
    public final String name;

    public Shapes(String name) {
        Events.add("Shapes body " + name);
        this.name = name;
    }

    public int count(String[] parts) {
        Events.add("count body " + parts.length);
        return parts.length;
    }

    public int parse(String text) {
        Events.add("parse body " + text);
        return Integer.parseInt(text);
    }

    public String pick(String first, long gap, String second) {
        Events.add("pick body " + first + " " + gap + " " + second);
        return first + gap + second;
    }
}
