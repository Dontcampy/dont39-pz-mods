package com.dont39.pz.viewpointbloodfxfix;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;

/**
 * Raises heights by the relief only for the duration of BloodFX's draw calls, so its simulation stays on flat ground.
 * Particle lists are only changed on the render thread, the same one that draws.
 */
public final class LiftOffsets {
    static final String PROBLEM;

    private static final List<?> DROPS, MISTS, PIECES;
    private static final Object[] SPLATS;
    private static final Field SPLAT_COUNT;
    private static final Coords DROP, MIST, SPLAT, PIECE;

    static {
        List<?> drops = null, mists = null, pieces = null;
        Object[] splats = null;
        Field splatCount = null;
        Coords drop = null, mist = null, splat = null, piece = null;
        String problem = null;
        try {
            Class<?> blood = Class.forName("bloodfx.BloodFx");
            drops = (List<?>) field(blood, "drops").get(null);
            mists = (List<?>) field(blood, "mists").get(null);
            splats = (Object[]) field(blood, "splats").get(null);
            splatCount = field(blood, "splatCount");
            pieces = (List<?>) field(Class.forName("bloodfx.GoreGpu"), "pieces").get(null);
            drop = new Coords(Class.forName("bloodfx.BloodFx$Drop"), "x", "y", "h");
            mist = new Coords(Class.forName("bloodfx.BloodFx$Mist"), "x", "y", "h");
            splat = new Coords(Class.forName("bloodfx.BloodFx$Splat"), "ox", "oy", "oh");
            piece = new Coords(Class.forName("bloodfx.Gore$Piece"), "x", "y", "h");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            problem = e.toString();
        }
        DROPS = drops;
        MISTS = mists;
        PIECES = pieces;
        SPLATS = splats;
        SPLAT_COUNT = splatCount;
        DROP = drop;
        MIST = mist;
        SPLAT = splat;
        PIECE = piece;
        PROBLEM = problem;
    }

    private static final Saved BLOOD = new Saved();
    private static final Saved GORE = new Saved();
    private static final Stats STATS = new Stats();

    private LiftOffsets() {
    }

    public static void liftBlood() {
        if (!ViewpointBloodFxFix.active()) {
            return;
        }
        BLOOD.restore(); // never stack on a lift whose exit did not run
        try {
            Object lift = Relief.current();
            if (lift != null) {
                for (int i = 0, n = DROPS.size(); i < n; i++) {
                    BLOOD.lift(lift, DROPS.get(i), DROP);
                }
                for (int i = 0, n = MISTS.size(); i < n; i++) {
                    BLOOD.lift(lift, MISTS.get(i), MIST);
                }
                for (int i = 0, n = Math.min(SPLAT_COUNT.getInt(null), SPLATS.length); i < n; i++) {
                    if (SPLATS[i] != null) {
                        BLOOD.lift(lift, SPLATS[i], SPLAT);
                    }
                }
            }
            if (ViewpointBloodFxFix.debug()) {
                STATS.frame(lift != null, BLOOD);
            }
        } catch (Throwable t) {
            BLOOD.restore();
            ViewpointBloodFxFix.disable("raising blood onto the terrain", t);
        }
    }

    public static void restoreBlood() {
        BLOOD.restore();
    }

    public static void liftGore() {
        if (!ViewpointBloodFxFix.active()) {
            return;
        }
        GORE.restore();
        try {
            Object lift = Relief.current();
            if (lift != null) {
                for (int i = 0, n = PIECES.size(); i < n; i++) {
                    GORE.lift(lift, PIECES.get(i), PIECE);
                }
            }
        } catch (Throwable t) {
            GORE.restore();
            ViewpointBloodFxFix.disable("raising severed parts onto the terrain", t);
        }
    }

    public static void restoreGore() {
        GORE.restore();
    }

    static Field field(Class<?> owner, String name) throws NoSuchFieldException {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    private static final class Coords {
        final Field x, y, h;

        Coords(Class<?> owner, String x, String y, String h) throws NoSuchFieldException {
            this.x = field(owner, x);
            this.y = field(owner, y);
            this.h = field(owner, h);
        }
    }

    /** Keeps the original heights so they are written back exactly, not recomputed. */
    private static final class Saved {
        private Object[] owners = new Object[256];
        private Field[] fields = new Field[256];
        private float[] heights = new float[256];
        private int count;
        float lowest, highest;

        void lift(Object lift, Object owner, Coords c) throws Throwable {
            float dh = Relief.at(lift, c.x.getFloat(owner), c.y.getFloat(owner));
            if (dh == 0.0F) {
                return;
            }
            float h = c.h.getFloat(owner);
            if (count == owners.length) {
                int size = count * 2;
                owners = Arrays.copyOf(owners, size);
                fields = Arrays.copyOf(fields, size);
                heights = Arrays.copyOf(heights, size);
            }
            owners[count] = owner;
            fields[count] = c.h;
            heights[count] = h;
            count++;
            c.h.setFloat(owner, h + dh);
            lowest = count == 1 ? dh : Math.min(lowest, dh);
            highest = count == 1 ? dh : Math.max(highest, dh);
        }

        void restore() {
            for (int i = count - 1; i >= 0; i--) {
                try {
                    fields[i].setFloat(owners[i], heights[i]);
                } catch (IllegalAccessException | RuntimeException e) {
                    // keep restoring the rest
                }
                owners[i] = null;
            }
            count = 0;
        }

        int count() {
            return count;
        }
    }

    private static final class Stats {
        private static final long EVERY = 5_000_000_000L;
        private long since = System.nanoTime();
        private int frames, withRelief, raised;
        private float lowest, highest;

        void frame(boolean relief, Saved saved) {
            frames++;
            if (relief) {
                withRelief++;
            }
            if (saved.count() > 0) {
                lowest = raised == 0 ? saved.lowest : Math.min(lowest, saved.lowest);
                highest = raised == 0 ? saved.highest : Math.max(highest, saved.highest);
                raised += saved.count();
            }
            long now = System.nanoTime();
            if (now - since >= EVERY) {
                ViewpointBloodFxFix.log(String.format("%d frames, %d with terrain relief, %d particle draws raised by %.3f..%.3f",
                        frames, withRelief, raised, lowest, highest));
                since = now;
                frames = withRelief = raised = 0;
            }
        }
    }
}
