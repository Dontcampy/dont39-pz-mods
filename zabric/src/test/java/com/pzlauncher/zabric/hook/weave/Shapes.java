package com.pzlauncher.zabric.hook.weave;

/**
 * A second weave target, for the shapes {@link Target} lacks: a constructor, an array, a long, a non-void throw, the
 * receiver beside a modified return value, the receiver and all arguments beside a modified argument, overloads
 * hooked apart from their longer ones.
 */
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

    public String label(String suffix) {
        Events.add("label body " + suffix);
        return name + suffix;
    }

    public String connect(String host, int port, String password, boolean relay) {
        Events.add("connect body " + host + " " + port + " " + password + " " + relay);
        return host + ":" + port;
    }

    public String add(String type) {
        Events.add("add body " + type);
        return name + ":" + type;
    }

    public boolean add(String type, float uses) {
        Events.add("add body " + type + " " + uses);
        return true;
    }

    public boolean add(String type, float uses, boolean synced) {
        Events.add("add body " + type + " " + uses + " " + synced);
        return synced;
    }
}
