package com.dont39.pz.loadingprogress.progress;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LogTest {
    private static Line mod(int i) {
        return new Line(Step.MODS, Item.MOD, i, 10, "m" + i);
    }

    private static List<Integer> indexes(Log log) {
        return log.lines().stream().map(Line::index).toList();
    }

    @Test
    void oldestFirstUntilFull() {
        Log log = new Log(3);
        assertEquals(List.of(), indexes(log));
        log.add(mod(1));
        log.add(mod(2));
        assertEquals(List.of(1, 2), indexes(log));
    }

    @Test
    void dropsTheOldestOnceFull() {
        Log log = new Log(3);
        IntStream.rangeClosed(1, 8).forEach(i -> log.add(mod(i)));
        assertEquals(List.of(6, 7, 8), indexes(log));
    }

    @Test
    void clearEmptiesIt() {
        Log log = new Log(3);
        IntStream.rangeClosed(1, 5).forEach(i -> log.add(mod(i)));
        log.clear();
        assertEquals(List.of(), indexes(log));
        log.add(mod(9));
        assertEquals(List.of(9), indexes(log));
    }
}
