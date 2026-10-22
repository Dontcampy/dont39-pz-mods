package com.dont39.pz.fasterloading.depth;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * The file-queue priorities that run a batch of depth-map tasks in a greedy list schedule: whenever one of
 * {@code workers} virtual workers is free, it takes the next task of the idle chain with the most estimated work left.
 * A chain is one {@code TileDepthTextures} instance, whose tasks decode one at a time under its lock.
 */
public final class Schedule {
    /** The game's priority for every depth-map task; the schedule's first task keeps it, the others go below. */
    public static final int GAME_PRIORITY = 4;

    private Schedule() {
    }

    /**
     * One priority per task, given in queue order with its chain id and estimated cost. Each chain keeps its own order;
     * a batch of fewer than two chains keeps the game's priority everywhere, which leaves its order as it is.
     */
    public static int[] priorities(int[] chain, long[] cost, int workers) {
        if (chain.length != cost.length) {
            throw new IllegalArgumentException(chain.length + " chain ids for " + cost.length + " costs");
        }
        if (workers < 1) {
            throw new IllegalArgumentException("no workers: " + workers);
        }
        int[] priorities = new int[chain.length];
        Arrays.fill(priorities, GAME_PRIORITY);
        // first appearance breaks ties, so that equal chains keep the game's order
        Map<Integer, Chain> byId = new LinkedHashMap<>();
        for (int task = 0; task < chain.length; task++) {
            if (cost[task] < 0) {
                throw new IllegalArgumentException("task " + task + " costs " + cost[task]);
            }
            byId.computeIfAbsent(chain[task], id -> new Chain(byId.size())).add(task, cost[task]);
        }
        if (byId.size() < 2) {
            return priorities;
        }
        List<Chain> chains = new ArrayList<>(byId.values());
        PriorityQueue<Finish> finishes = new PriorityQueue<>(
                Comparator.comparingLong(Finish::time).thenComparingInt(f -> f.chain().rank));
        int free = workers;
        long now = 0;
        int emitted = 0;
        while (emitted < chain.length) {
            while (free > 0) {
                Chain next = null;
                for (Chain c : chains) {
                    if (!c.running && c.hasNext() && (next == null || c.left > next.left)) {
                        next = c;
                    }
                }
                if (next == null) {
                    break;
                }
                int task = next.take();
                priorities[task] = GAME_PRIORITY - emitted++;
                finishes.add(new Finish(now + cost[task], next));
                free--;
            }
            Finish finish = finishes.remove();
            now = finish.time();
            finish.chain().running = false;
            free++;
        }
        return priorities;
    }

    private record Finish(long time, Chain chain) {
    }

    private static final class Chain {
        final int rank;
        final List<Integer> tasks = new ArrayList<>();
        final List<Long> costs = new ArrayList<>();
        long left;
        int next;
        boolean running;

        Chain(int rank) {
            this.rank = rank;
        }

        void add(int task, long cost) {
            tasks.add(task);
            costs.add(cost);
            left += cost;
        }

        boolean hasNext() {
            return next < tasks.size();
        }

        int take() {
            left -= costs.get(next);
            running = true;
            return tasks.get(next++);
        }
    }
}
