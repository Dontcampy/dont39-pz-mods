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

        /** A pair of the outermost table that each side writes for itself, so it cannot match. */
        default boolean ownPerSide(Object key, Object value) {
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
            if (tables.saved(k, v) && !(depth == 0 && tables.ownPerSide(k, v))) {
                pairs.add(Digest.of(value(k, tables, depth), value(v, tables, depth)));
            }
        });
        return pairs.get();
    }

    /**
     * The pairs {@link #hash} covers, as {@code path -> value} for the field diff: nested tables down to
     * {@code maxDepth} levels, deeper ones as their hash. Paths join keys with dots; the order is the table's own.
     */
    public static void describe(Object table, Tables tables, int maxDepth, BiConsumer<String, String> out) {
        describe(table, tables, "", 0, maxDepth, out);
    }

    // returns how many values it described; an empty nested table is one, "{}"
    private static int describe(Object table, Tables tables, String prefix, int depth, int maxDepth,
            BiConsumer<String, String> out) {
        int[] n = {0};
        tables.forEach(table, (k, v) -> {
            if (!tables.saved(k, v) || (depth == 0 && tables.ownPerSide(k, v))) {
                return;
            }
            String path = prefix + k;
            if (tables.isTable(v) && depth < maxDepth) {
                if (describe(v, tables, path + ".", depth + 1, maxDepth, out) == 0) {
                    out.accept(path, "{}");
                }
            } else {
                out.accept(path, summary(v, tables, depth));
            }
            n[0]++;
        });
        return n[0];
    }

    private static String summary(Object v, Tables tables, int depth) {
        if (v instanceof String s) {
            return '"' + (s.length() > 40 ? s.substring(0, 40) + "..." : s) + '"';
        }
        if (v instanceof Number n) {
            double d = n.doubleValue();
            return d == Math.rint(d) && Math.abs(d) < 1e15 ? Long.toString((long) d) : Double.toString(d);
        }
        if (tables.isTable(v)) {
            return String.format("table#%016x", table(v, tables, depth + 1));
        }
        return String.valueOf(v);
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
