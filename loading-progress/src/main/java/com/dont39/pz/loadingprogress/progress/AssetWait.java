package com.dont39.pz.loadingprogress.progress;

import java.util.List;

/**
 * The world load's wait for the game's file tasks, between the game's log lines that bracket it: {@code start}
 * nanoseconds after the flow began, lasting {@code nanos}; {@code startMillis} and {@code endMillis} are the wall clock,
 * to compare with the game's log. {@code ended} is false when the flow ended during it. {@code frames}: pumps of the
 * file queue during it; at the first, {@code queuedPending} tasks waited to go to the workers and
 * {@code queuedAtWorkers} were there. {@code busyNanos}: the time {@code workers} worker threads spent on tasks during
 * it; {@code throttleNanos}: of that, the time texture decoders waited for the upload budget, {@code throttledCalls}
 * of them for longer than a moment. {@code runs}: the tasks whose results the game took during it, empty while the
 * report is off.
 */
public record AssetWait(long start, long nanos, long startMillis, long endMillis, boolean ended, int frames,
                        int queuedPending, int queuedAtWorkers, int workers, long busyNanos, long throttleNanos,
                        int throttledCalls, List<FileWork.Run> runs) {
    /** The game's log lines around the wait, on the world's loading thread. */
    public static final String BEGINS = "bWaitForAssetLoadingToFinish2 start";
    public static final String ENDS = "bWaitForAssetLoadingToFinish2 end";
}
