package com.dont39.pz.loadingprogress.draw;

import com.dont39.pz.loadingprogress.progress.Progress;

import java.util.List;
import java.util.function.ToIntFunction;

/**
 * Where the bar and the log go: a column at the screen's right edge, the bar at its top, the log under it down to the
 * highest vanilla content that reaches into the column. Beside a centered panel it moves into the strip right of it;
 * where that strip is too narrow for text, only the bar shows. Screen pixels; no game classes.
 */
record Column(int left, int right, int top, int logTop, int rows) {
    static final int MARGIN = 12;
    static final int PAD = 8;
    static final int GAP = 4;
    static final int BAR_HEIGHT = 8;
    // about 640 px at the 1x Small font's 19 px rows
    static final int WIDTH_ROWS = 34;
    static final int WIDTH_BEFORE_FONT = 640;
    static final int MIN_WIDTH_ROWS = 6;
    static final String ELLIPSIS = "...";

    /** Vanilla content starting at {@code top} whose right edge is {@code right}: the log stops above it. */
    record Block(int top, int right) {
    }

    /**
     * {@code top}: the box's top edge; {@code lineHeight}: the Small font's, 0 while it loads (bar only);
     * {@code panelRight}: the right edge of a centered panel to stay beside, 0 for none; {@code blocks}: null while
     * they cannot be measured (bar only).
     */
    static Column place(int screenWidth, int screenHeight, int top, int lineHeight, int panelRight,
                        List<Block> blocks) {
        int right = screenWidth - MARGIN;
        int width = Math.min(lineHeight > 0 ? WIDTH_ROWS * lineHeight : WIDTH_BEFORE_FONT, screenWidth * 3 / 10);
        int left = right - width;
        boolean text = lineHeight > 0 && blocks != null;
        if (panelRight > 0) {
            int beside = panelRight + MARGIN;
            if (right - beside >= MIN_WIDTH_ROWS * lineHeight) {
                left = Math.max(left, beside);
            } else {
                text = false;
            }
        }
        int logTop = top + PAD + BAR_HEIGHT + GAP;
        if (!text) {
            return new Column(left, right, top, logTop, 0);
        }
        int bottom = screenHeight - MARGIN;
        for (Block block : blocks) {
            if (block.right() > left - MARGIN) {
                bottom = Math.min(bottom, block.top() - MARGIN);
            }
        }
        int rows = Math.max(0, Math.min((bottom - PAD - logTop) / lineHeight, Progress.LOG_LINES));
        return new Column(left, right, top, logTop, rows);
    }

    /** Where text starts. */
    int textLeft() {
        return left + PAD;
    }

    int textWidth() {
        return right - left - 2 * PAD;
    }

    /** {@code line}, or its longest start that fits {@code max} with an ellipsis after it. */
    static String fit(String line, int max, ToIntFunction<String> width) {
        if (width.applyAsInt(line) <= max) {
            return line;
        }
        // the longest start that fits, by bisection: the measure is monotonic in the length
        int fits = 0;
        int over = line.length();
        while (over - fits > 1) {
            int mid = (fits + over) >>> 1;
            if (width.applyAsInt(cut(line, mid)) <= max) {
                fits = mid;
            } else {
                over = mid;
            }
        }
        return fits == 0 && width.applyAsInt(ELLIPSIS) > max ? "" : cut(line, fits);
    }

    private static String cut(String line, int length) {
        // never between the halves of a surrogate pair
        if (length > 0 && Character.isHighSurrogate(line.charAt(length - 1))) {
            length--;
        }
        return line.substring(0, length).stripTrailing() + ELLIPSIS;
    }
}
