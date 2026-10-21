package com.dont39.pz.loadingprogress.progress;

/**
 * A line of the log under the bar: a step that began ({@code item} null, {@code index} its number of {@code total}
 * steps) or an item; the text is built when drawn.
 */
public record Line(Step step, Item item, int index, int total, String name) {
    static Line step(Step step, int number, int steps) {
        return new Line(step, null, number, steps, null);
    }

    public boolean isStep() {
        return item == null;
    }
}
