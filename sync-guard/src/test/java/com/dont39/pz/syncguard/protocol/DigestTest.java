package com.dont39.pz.syncguard.protocol;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DigestTest {
    /** Maps as Lua tables; a "fn" value stands for one the game does not save. */
    private static final LuaTableHash.Tables MAPS = new LuaTableHash.Tables() {
        @Override
        public boolean isTable(Object value) {
            return value instanceof Map;
        }

        @Override
        public void forEach(Object table, BiConsumer<Object, Object> pair) {
            ((Map<?, ?>) table).forEach(pair);
        }

        @Override
        public boolean saved(Object key, Object value) {
            return !"fn".equals(value);
        }
    };

    @Test
    void sameValuesSameDigest() {
        assertEquals(new Digest().putInt(1).putString("a").putFloat(0.5f).get(),
                new Digest().putInt(1).putString("a").putFloat(0.5f).get());
    }

    @Test
    void orderMattersInASequence() {
        assertNotEquals(new Digest().putInt(1).putInt(2).get(), new Digest().putInt(2).putInt(1).get());
    }

    @Test
    void boundariesMatter() {
        assertNotEquals(new Digest().putString("ab").putString("c").get(),
                new Digest().putString("a").putString("bc").get());
        assertNotEquals(new Digest().putString(null).get(), new Digest().putString("").get());
        assertNotEquals(new Digest().get(), new Digest().putLong(0).get());
    }

    @Test
    void bytesOfEveryLength() {
        Set<Long> seen = new HashSet<>();
        byte[] data = new byte[20];
        for (int n = 0; n <= data.length; n++) {
            seen.add(new Digest().putBytes(data, 0, n).get());
        }
        assertEquals(data.length + 1, seen.size());
    }

    @Test
    void floatsByBits() {
        assertEquals(new Digest().putFloat(Float.NaN).get(), new Digest().putFloat(Float.intBitsToFloat(0x7FC00001))
                .get());
        assertNotEquals(new Digest().putFloat(0f).get(), new Digest().putFloat(-0f).get());
    }

    @Test
    void containerDigestIgnoresItemOrder() {
        long a = new ContainerDigest().add(1, "Base.Apple", 10).add(2, "Base.Axe", 20).add(3, "Base.Bag", 30).get();
        long b = new ContainerDigest().add(3, "Base.Bag", 30).add(1, "Base.Apple", 10).add(2, "Base.Axe", 20).get();
        assertEquals(a, b);
    }

    @Test
    void containerDigestCoversIdTypeStateAndCount() {
        long base = new ContainerDigest().add(1, "Base.Apple", 10).add(2, "Base.Axe", 20).get();
        assertNotEquals(base, new ContainerDigest().add(9, "Base.Apple", 10).add(2, "Base.Axe", 20).get());
        assertNotEquals(base, new ContainerDigest().add(1, "Base.Pear", 10).add(2, "Base.Axe", 20).get());
        assertNotEquals(base, new ContainerDigest().add(1, "Base.Apple", 11).add(2, "Base.Axe", 20).get());
        assertNotEquals(base, new ContainerDigest().add(1, "Base.Apple", 10).get());
        assertNotEquals(base, new ContainerDigest().add(1, "Base.Apple", 10).add(2, "Base.Axe", 20)
                .add(2, "Base.Axe", 20).get());
        assertNotEquals(new ContainerDigest().get(), base);
    }

    @Test
    void swappedStatesDiffer() {
        // the pair is hashed together, so two items cannot trade their states unnoticed
        assertNotEquals(new ContainerDigest().add(1, "Base.Apple", 10).add(2, "Base.Apple", 20).get(),
                new ContainerDigest().add(1, "Base.Apple", 20).add(2, "Base.Apple", 10).get());
    }

    @Test
    void luaTablesIgnorePairOrder() {
        Map<Object, Object> a = new LinkedHashMap<>();
        a.put("name", "x");
        a.put(1.0, true);
        a.put("nested", new LinkedHashMap<>(Map.of("k", 2.0)));
        Map<Object, Object> b = new LinkedHashMap<>();
        b.put("nested", new LinkedHashMap<>(Map.of("k", 2.0)));
        b.put(1.0, true);
        b.put("name", "x");
        assertEquals(LuaTableHash.hash(a, MAPS), LuaTableHash.hash(b, MAPS));
    }

    @Test
    void luaTablesCoverKeysValuesAndNesting() {
        long base = LuaTableHash.hash(Map.of("k", 1.0), MAPS);
        assertNotEquals(base, LuaTableHash.hash(Map.of("k", 2.0), MAPS));
        assertNotEquals(base, LuaTableHash.hash(Map.of("j", 1.0), MAPS));
        assertNotEquals(base, LuaTableHash.hash(Map.of("k", "1"), MAPS));
        assertNotEquals(base, LuaTableHash.hash(Map.of("k", Map.of("k", 1.0)), MAPS));
        // one Lua number type
        assertEquals(base, LuaTableHash.hash(Map.of("k", 1), MAPS));
    }

    @Test
    void emptyAndUnsavedPairs() {
        assertEquals(LuaTableHash.EMPTY, LuaTableHash.hash(Map.of(), MAPS));
        assertEquals(LuaTableHash.EMPTY, LuaTableHash.hash(Map.of("f", "fn"), MAPS));
        assertEquals(LuaTableHash.hash(Map.of("k", 1.0), MAPS), LuaTableHash.hash(Map.of("k", 1.0, "f", "fn"), MAPS));
    }

    @Test
    void aCycleIsAnError() {
        Map<Object, Object> loop = new LinkedHashMap<>();
        loop.put("self", loop);
        assertThrows(IllegalStateException.class, () -> LuaTableHash.hash(loop, MAPS));
    }

    @Test
    void anUnknownSavedValueIsAnError() {
        assertThrows(IllegalStateException.class, () -> LuaTableHash.hash(Map.of("k", new Object()), MAPS));
    }

    @Test
    void containerKeysAreValues() {
        assertEquals(ContainerKey.player(5), ContainerKey.player(5));
        assertEquals(ContainerKey.player(5).hashCode(), ContainerKey.player(5).hashCode());
        assertNotEquals(ContainerKey.player(5), ContainerKey.bag(5, 0));
        assertNotEquals(ContainerKey.object(1, 2, 0, 3), ContainerKey.objectContainer(1, 2, 0, 3, 0));
        assertEquals(new ItemKey(ContainerKey.vehicle(4, 2), 9), new ItemKey(ContainerKey.vehicle(4, 2), 9));
    }
}
