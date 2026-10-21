package com.dont39.pz.loadingprogress.progress;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * The game's file tasks while the world loads, for the asset wait's part of the report. Game-free: the hooks pass the
 * workers' calls, and on each pump of the queue the tasks at the workers, each described once. Workers call in on
 * their own threads, the pump on the thread that runs it.
 */
public final class FileWork {
    // a texture decoder that waited at least this long slept on the upload budget; the game sleeps 20 ms at a time
    static final long SLEPT = TimeUnit.MILLISECONDS.toNanos(1);

    /**
     * A task as the hooks describe it: {@code type} its class, {@code worker} the hook that times its call (the class
     * that declares it), {@code name} its file as the game names it (null when it has none), unless its call resolves
     * one ({@link #callFile}); {@code future} its result once a worker ran it.
     */
    public record Task(String type, String worker, String name, Future<?> future) {
    }

    /**
     * A task whose result the game took during the asset wait. {@code queued}: when it went to the workers, in
     * nanoseconds after the flow began, or -1 when it was there before the world load began; {@code done}: when the
     * game took its result; {@code workerNanos}: its call on a worker, or -1 when not timed.
     */
    public record Run(String type, String name, long queued, long done, long workerNanos, boolean failed) {
    }

    // a task at the workers, from the pump that sent it
    private record Sent(Task task, long queued) {
    }

    // a call's time on its worker and the file it resolved, or null
    private record Worked(long nanos, String file) {
    }

    // the call running on a worker thread, if it began while tracking
    private static final class Call {
        boolean begun;
        long start;
        String file;
    }

    private final ThreadLocal<Call> calls = ThreadLocal.withInitial(Call::new);
    // per thread: whether a sleep on the upload budget began during the wait, and when
    private final ThreadLocal<long[]> throttles = ThreadLocal.withInitial(() -> new long[2]);

    private volatile boolean tracking;
    private volatile long trackingSince;
    private volatile boolean waiting;
    private volatile long waitStarted;

    // the flow's start, which run times count from
    private long base;
    // in the order sent; the game's items are equal only to themselves
    private final Map<Object, Sent> sent = new LinkedHashMap<>();
    // workers' times by their result; a result the game shares between tasks (null, Boolean) only by hook, so such
    // tasks of one hook may swap times with each other
    private final Map<Object, ArrayDeque<Worked>> byResult = new IdentityHashMap<>();
    private final Map<String, ArrayDeque<Worked>> byWorker = new HashMap<>();
    private boolean pumped;

    private int frames;
    private int queuedPending;
    private int queuedAtWorkers;
    private final Set<Thread> workers = Collections.newSetFromMap(new IdentityHashMap<>());
    private long busy;
    private long throttled;
    private int throttledCalls;
    private List<Run> runs = new ArrayList<>();

    /** Whether the workers' calls and the queue's pumps count: a cheap first check for the hooks. */
    public boolean tracking() {
        return tracking;
    }

    /** Whether the asset wait runs. */
    public boolean waiting() {
        return waiting;
    }

    /** A worker begins a task's call. */
    public void callBegins(long now) {
        if (tracking) {
            Call call = calls.get();
            call.begun = true;
            call.start = now;
            call.file = null;
        }
    }

    /** The call running on this thread resolved its file to {@code path}; outside a call, nothing. */
    public void callFile(String path) {
        if (tracking) {
            Call call = calls.get();
            if (call.begun) {
                call.file = path;
            }
        }
    }

    /**
     * The call ended: {@code worker} names the hook, as {@link Task#worker()} does; {@code result} is what it returned,
     * {@code thrown} what it threw.
     */
    public void callEnds(String worker, Object result, Throwable thrown, long now) {
        if (!tracking) {
            return;
        }
        Call call = calls.get();
        boolean begun = call.begun;
        long start = call.start;
        String file = call.file;
        call.begun = false;
        call.file = null;
        // a call that began before tracking did is not timed
        if (!begun || start - trackingSince < 0) {
            return;
        }
        Worked worked = new Worked(now - start, file);
        synchronized (this) {
            if (!tracking) {
                return;
            }
            if (thrown == null && shared(result)) {
                byWorker.computeIfAbsent(worker, key -> new ArrayDeque<>()).add(worked);
            } else {
                byResult.computeIfAbsent(thrown != null ? thrown : result, key -> new ArrayDeque<>()).add(worked);
            }
            if (waiting) {
                workers.add(Thread.currentThread());
                busy += now - Math.max(start, waitStarted);
            }
        }
    }

    /** A texture decoder begins to wait for the upload budget. */
    public void throttleBegins(long now) {
        if (waiting) {
            long[] start = throttles.get();
            start[0] = 1;
            start[1] = now;
        }
    }

    public void throttleEnds(long now) {
        if (!waiting) {
            return;
        }
        long start = throttleStart(waitStarted);
        if (start == Long.MIN_VALUE) {
            return;
        }
        synchronized (this) {
            if (waiting) {
                throttled += now - start;
                if (now - start >= SLEPT) {
                    throttledCalls++;
                }
            }
        }
    }

    /**
     * The game pumped its file queue: {@code atWorkers} are the items it sent to the workers and has not taken back,
     * {@code pending} how many wait to be sent. A sent item no longer there is done; {@code describe} is asked once per
     * item, while it is at the workers.
     */
    public synchronized <T> void pumped(long now, List<T> atWorkers, int pending, Function<? super T, Task> describe) {
        if (!tracking) {
            return;
        }
        Set<Object> current = Collections.newSetFromMap(new IdentityHashMap<>());
        current.addAll(atWorkers);
        sent.entrySet().removeIf(entry -> {
            if (current.contains(entry.getKey())) {
                return false;
            }
            done(entry.getValue(), now);
            return true;
        });
        for (T item : atWorkers) {
            if (!sent.containsKey(item)) {
                sent.put(item, new Sent(describe.apply(item), pumped ? now - base : -1));
            }
        }
        pumped = true;
        if (waiting && frames++ == 0) {
            queuedPending = pending;
            queuedAtWorkers = atWorkers.size();
        }
    }

    /** Tracks while {@code on}; turning it on starts afresh, with run times from {@code flowStarted}. */
    synchronized void track(boolean on, long flowStarted, long now) {
        if (on == tracking) {
            return;
        }
        clearTracked();
        if (on) {
            base = flowStarted;
            trackingSince = now;
        }
        tracking = on;
    }

    synchronized void waitBegins(long now) {
        waiting = true;
        waitStarted = now;
        frames = 0;
        queuedPending = 0;
        queuedAtWorkers = 0;
        workers.clear();
        busy = 0;
        throttled = 0;
        throttledCalls = 0;
        runs = new ArrayList<>();
    }

    /**
     * The wait, {@code start} nanoseconds after the flow began, ended {@code nanos} later, or the flow ended during it
     * ({@code ended} false).
     */
    synchronized AssetWait waitEnds(long start, long nanos, long startMillis, long endMillis, boolean ended) {
        waiting = false;
        AssetWait wait = new AssetWait(start, nanos, startMillis, endMillis, ended, frames, queuedPending,
                queuedAtWorkers, workers.size(), busy, throttled, throttledCalls, List.copyOf(runs));
        workers.clear();
        runs = new ArrayList<>();
        return wait;
    }

    private void done(Sent item, long now) {
        Task task = item.task();
        Future<?> future = task.future();
        if (!future.isDone()) {
            throw new IllegalStateException("the game took file task " + task.type() + " (" + task.name()
                    + ") from its workers before it was done");
        }
        Worked worked = null;
        boolean failed = true;
        if (!future.isCancelled()) {
            try {
                Object result = future.get();
                failed = false;
                worked = shared(result) ? take(byWorker, task.worker()) : take(byResult, result);
            } catch (ExecutionException e) {
                worked = take(byResult, e.getCause());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted reading the result of a done file task", e);
            }
        }
        if (waiting) {
            runs.add(new Run(task.type(), worked != null && worked.file() != null ? worked.file() : task.name(),
                    item.queued(), now - base, worked != null ? worked.nanos() : -1, failed));
        }
    }

    private void clearTracked() {
        sent.clear();
        byResult.clear();
        byWorker.clear();
        pumped = false;
    }

    // an emptied queue goes, so that the map holds no result the game is done with
    private static <K> Worked take(Map<K, ArrayDeque<Worked>> times, K key) {
        ArrayDeque<Worked> queue = times.get(key);
        if (queue == null) {
            return null;
        }
        Worked worked = queue.poll();
        if (queue.isEmpty()) {
            times.remove(key);
        }
        return worked;
    }

    private static boolean shared(Object result) {
        return result == null || result instanceof Boolean;
    }

    // the start of this thread's sleep, or MIN_VALUE when it began before since or was not seen
    private long throttleStart(long since) {
        long[] start = throttles.get();
        boolean begun = start[0] != 0;
        start[0] = 0;
        return begun && start[1] - since >= 0 ? start[1] : Long.MIN_VALUE;
    }
}
