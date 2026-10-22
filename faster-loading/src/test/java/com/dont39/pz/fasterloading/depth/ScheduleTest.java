package com.dont39.pz.fasterloading.depth;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScheduleTest {
    // the measured join's chains in queue order, the game's last and longest
    private static final int[] JOIN = {144, 116, 102, 55, 32, 28, 27, 9, 4, 1, 216};

    private record Fixture(int[] chain, long[] cost) {
    }

    // the queue order the priorities give: highest first, the game's insertion keeps equal ones in queue order
    private static int[] order(int[] priorities) {
        return IntStream.range(0, priorities.length).boxed()
                .sorted(Comparator.comparingInt((Integer t) -> -priorities[t])).mapToInt(Integer::intValue).toArray();
    }

    private static int[] order(Fixture f, int workers) {
        return order(Schedule.priorities(f.chain, f.cost, workers));
    }

    // Every worker takes the next task in order and holds it while it waits for the chain's lock, as the game's do.
    private static long finish(Fixture f, int[] order, int workers) {
        PriorityQueue<Long> free = new PriorityQueue<>();
        for (int i = 0; i < workers; i++) {
            free.add(0L);
        }
        Map<Integer, Long> unlocked = new HashMap<>();
        long end = 0;
        for (int task : order) {
            long start = Math.max(free.remove(), unlocked.getOrDefault(f.chain[task], 0L));
            long done = start + f.cost[task];
            unlocked.put(f.chain[task], done);
            free.add(done);
            end = Math.max(end, done);
        }
        return end;
    }

    // tileset rows of 1 to 16, the game's chain with its largest maps late in its order
    private static Fixture join() {
        Random random = new Random(7);
        int n = Arrays.stream(JOIN).sum();
        int[] chain = new int[n];
        long[] cost = new long[n];
        int task = 0;
        for (int c = 0; c < JOIN.length; c++) {
            for (int k = 0; k < JOIN[c]; k++, task++) {
                chain[task] = c;
                cost[task] = c == JOIN.length - 1 && k >= 160 && k <= 166 ? 64 : 1 + random.nextInt(16);
            }
        }
        return new Fixture(chain, cost);
    }

    @Test
    void twoChainsAlternate() {
        Fixture f = new Fixture(new int[] {0, 0, 0, 1, 1, 1}, new long[] {1, 1, 1, 1, 1, 1});
        assertArrayEquals(new int[] {0, 3, 1, 4, 2, 5}, order(f, 4));
    }

    @Test
    void manyChainsAlternate() {
        Fixture f = new Fixture(new int[] {0, 0, 1, 1, 2, 2}, new long[] {1, 1, 1, 1, 1, 1});
        assertArrayEquals(new int[] {0, 2, 4, 1, 3, 5}, order(f, 4));
    }

    @Test
    void theChainWithTheMostWorkStartsFirst() {
        Fixture f = new Fixture(new int[] {0, 0, 0, 1, 1}, new long[] {1, 1, 1, 5, 5});
        assertEquals(3, order(f, 4)[0]);
    }

    @Test
    void prioritiesAreDistinctAndAtMostTheGames() {
        Fixture f = join();
        int[] priorities = Schedule.priorities(f.chain, f.cost, 4);
        assertEquals(Schedule.GAME_PRIORITY, Arrays.stream(priorities).max().orElseThrow());
        assertEquals(priorities.length, Arrays.stream(priorities).distinct().count());
    }

    @Test
    void eachChainKeepsItsOrder() {
        Fixture f = join();
        int[] last = new int[JOIN.length];
        Arrays.fill(last, -1);
        for (int task : order(f, 4)) {
            assertTrue(task > last[f.chain[task]], "task " + task + " after " + last[f.chain[task]]);
            last[f.chain[task]] = task;
        }
    }

    @Test
    void aJoinFinishesNearTheBound() {
        Fixture f = join();
        long[] work = new long[JOIN.length];
        for (int t = 0; t < f.chain.length; t++) {
            work[f.chain[t]] += f.cost[t];
        }
        long bound = Math.max(Arrays.stream(f.cost).sum() / 4, Arrays.stream(work).max().orElseThrow());
        assertEquals(work[JOIN.length - 1], bound, "the game's chain sets the bound");
        int[] scheduled = order(f, 4);
        assertEquals(JOIN.length - 1, f.chain[scheduled[0]], "the game's chain starts first");
        long greedy = finish(f, scheduled, 4);
        long queued = finish(f, IntStream.range(0, f.chain.length).toArray(), 4);
        assertTrue(greedy <= bound * 1.02, "scheduled " + greedy + ", bound " + bound);
        assertTrue(queued > 2 * greedy, "as queued " + queued + ", scheduled " + greedy);
    }

    @Test
    void anEmptyBatchStaysEmpty() {
        assertEquals(0, Schedule.priorities(new int[0], new long[0], 4).length);
    }

    @Test
    void oneChainKeepsTheGamesPriority() {
        int[] priorities = Schedule.priorities(new int[] {3, 3, 3}, new long[] {5, 1, 9}, 4);
        assertArrayEquals(new int[] {4, 4, 4}, priorities);
    }

    @Test
    void badInputThrows() {
        assertThrows(IllegalArgumentException.class, () -> Schedule.priorities(new int[1], new long[2], 4));
        assertThrows(IllegalArgumentException.class, () -> Schedule.priorities(new int[1], new long[1], 0));
        assertThrows(IllegalArgumentException.class, () -> Schedule.priorities(new int[] {0, 1}, new long[] {1, -1}, 4));
    }

    // the task's simulator, research/reprioritise_sim.py order_greedy, on a generated batch
    @Test
    void theOrderIsTheSimulators() throws IOException {
        List<Integer> chain = new ArrayList<>();
        List<Long> cost = new ArrayList<>();
        Map<Integer, int[]> expected = new HashMap<>();
        try (InputStream in = ScheduleTest.class.getResourceAsStream("greedy-batch.txt")) {
            assertNotNull(in);
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList()) {
                String[] words = line.split(" ");
                switch (words[0]) {
                    case "task" -> {
                        chain.add(Integer.parseInt(words[1]));
                        cost.add(Long.parseLong(words[2]));
                    }
                    case "order" -> expected.put(Integer.parseInt(words[1]),
                            Arrays.stream(words, 2, words.length).mapToInt(Integer::parseInt).toArray());
                    default -> assertTrue(line.startsWith("#"), line);
                }
            }
        }
        Fixture f = new Fixture(chain.stream().mapToInt(Integer::intValue).toArray(),
                cost.stream().mapToLong(Long::longValue).toArray());
        assertEquals(List.of(2, 4), expected.keySet().stream().sorted().toList());
        expected.forEach((workers, order) -> assertArrayEquals(order, order(f, workers), workers + " workers"));
    }
}
