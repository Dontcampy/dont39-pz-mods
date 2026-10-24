package com.dont39.pz.syncguard.protocol;

import java.util.function.BiConsumer;

/**
 * The hash of a Lua table, independent of pair order: two copies of one table iterate in different orders. An empty
 * table hashes as {@link #EMPTY}, which callers also use for a missing one, as the game sends neither.
 */
public final class LuaTableHash {
    public static final long EMPTY = new Unordered().get();

    /** Deeper than any table the game saves; a deeper one is a cycle. */
    static final int MAX_DEPTH = 32;

    /** How the caller's tables look. */
    public interface Tables {
        boolean isTable(Object value);

        void forEach(Object table, BiConsumer<Object, Object> pair);

        /** Whether the game saves and sends the pair; it drops the others, so they cannot match. */
        boolean saved(Object key, Object value);

        /** A key of the outermost table that each side writes for itself, so it cannot match. */
        default boolean ownPerSide(Object key) {
            return false;
        }
    }

    private LuaTableHash() {
    }

    public static long hash(Object table, Tables tables) {
        return table(table, tables, 0);
    }

    private static long table(Object table, Tables tables, int depth) {
        if (depth > MAX_DEPTH) {
            throw new IllegalStateException("a Lua table nested deeper than " + MAX_DEPTH + " levels");
        }
        Unordered pairs = new Unordered();
        tables.forEach(table, (k, v) -> {
            if (tables.saved(k, v) && !(depth == 0 && tables.ownPerSide(k))) {
                pairs.add(Digest.of(value(k, tables, depth), value(v, tables, depth)));
            }
        });
        return pairs.get();
    }

    private static long value(Object v, Tables tables, int depth) {
        Digest d = new Digest();
        if (v == null) {
            d.putInt(0);
        } else if (v instanceof Boolean b) {
            d.putInt(1).putBoolean(b);
        } else if (v instanceof Number n) {
            // Lua has one number type; the game stores Doubles, a mod may put an Integer
            d.putInt(2).putLong(Double.doubleToLongBits(n.doubleValue()));
        } else if (v instanceof String s) {
            d.putInt(3).putString(s);
        } else if (tables.isTable(v)) {
            d.putInt(4).putLong(table(v, tables, depth + 1));
        } else {
            throw new IllegalStateException("a saved Lua table holds a " + v.getClass().getName());
        }
        return d.get();
    }
}
