package com.dont39.pz.fasterloading.depth;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** The depth-map tasks one {@code TileDepthTextureManager.init} queued, checked against what the game does. */
public final class Batch {
    private Batch() {
    }

    /**
     * A queued task: its {@code TileDepthTextures} instance (by identity) and that instance's mod id and tileset rows,
     * its file name and its priority.
     */
    public record Task(Object instance, String mod, Map<String, Integer> rows, String file, int priority) {
    }

    /** The priority of each task, the batch's chain count, and the chain with the most work (null mod if none). */
    public record Plan(int[] priorities, int chains, String longestMod, int longestTasks) {
    }

    /**
     * The new priorities of {@code tasks}, in queue order, of which the game's counter says it queued {@code queued};
     * throws naming the first task that is not as the game queues it.
     */
    public static Plan plan(List<Task> tasks, int queued, int workers) {
        if (tasks.size() != queued) {
            throw new IllegalStateException("the game counted " + queued + " new depth-map tasks, its queue holds "
                    + tasks.size() + (tasks.isEmpty() ? "" : ", the first " + describe(tasks.get(0))));
        }
        int[] chain = new int[tasks.size()];
        long[] cost = new long[tasks.size()];
        Map<Object, Chain> byInstance = new IdentityHashMap<>();
        List<Chain> chains = new ArrayList<>();
        for (int i = 0; i < tasks.size(); i++) {
            Task task = tasks.get(i);
            if (task.priority() != Schedule.GAME_PRIORITY) {
                throw new IllegalStateException("depth-map task " + describe(task) + " has priority " + task.priority()
                        + ", the game queues them at " + Schedule.GAME_PRIORITY);
            }
            String tileset = tileset(task.file());
            Integer rows = task.rows().get(tileset);
            if (rows == null || rows < 0) {
                throw new IllegalStateException("depth-map task " + describe(task) + ": its mod's tileset rows have "
                        + (rows == null ? "no entry" : rows) + " for " + tileset);
            }
            Chain c = byInstance.computeIfAbsent(task.instance(), instance -> {
                Chain added = new Chain(chains.size(), task.mod());
                chains.add(added);
                return added;
            });
            chain[i] = c.id;
            cost[i] = rows;
            c.rows += rows;
            c.tasks++;
        }
        Chain longest = chains.stream().max(Comparator.comparingLong(c -> c.rows)).orElse(new Chain(0, null));
        return new Plan(Schedule.priorities(chain, cost, workers), chains.size(), longest.mod, longest.tasks);
    }

    /** The tileset a depth map's file is of, as the game's task derives it. */
    static String tileset(String file) {
        return file.replaceFirst("DEPTH_", "").replace(".png", "");
    }

    private static String describe(Task task) {
        return task.file() + " of " + task.mod();
    }

    private static final class Chain {
        final int id;
        final String mod;
        long rows;
        int tasks;

        Chain(int id, String mod) {
            this.id = id;
            this.mod = mod;
        }
    }
}
