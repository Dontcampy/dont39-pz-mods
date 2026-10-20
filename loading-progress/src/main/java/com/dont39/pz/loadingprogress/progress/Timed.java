package com.dont39.pz.loadingprogress.progress;

/**
 * A line of a flow's log with its time: {@code start} nanoseconds after the flow began, lasting {@code nanos} until the
 * next line, a wait in the menu or the flow's end. {@code path}: the full path of a Lua or script file, else null.
 */
public record Timed(Line line, String path, long start, long nanos) {
}
