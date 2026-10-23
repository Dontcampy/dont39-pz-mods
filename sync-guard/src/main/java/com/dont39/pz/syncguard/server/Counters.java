package com.dont39.pz.syncguard.server;

/**
 * What the server did since it started, per connection and, adding up, for all of them. Main thread only.
 */
final class Counters {
    private final Counters total;
    private long frames;
    private long entries;
    private long gone;
    private long checks;
    private long mismatches;
    private long requested;
    private long refused;
    private long snapshots;
    private long tooLarge;
    private long bytes;

    /** {@code total}: also counted there; null for the total itself. */
    Counters(Counters total) {
        this.total = total;
    }

    void frame(int entries, int gone, int bytes) {
        this.frames++;
        this.entries += entries;
        this.gone += gone;
        this.bytes += bytes;
        if (total != null) {
            total.frame(entries, gone, bytes);
        }
    }

    void check(boolean differed) {
        checks++;
        if (differed) {
            mismatches++;
        }
        if (total != null) {
            total.check(differed);
        }
    }

    void requested(boolean refused) {
        requested++;
        if (refused) {
            this.refused++;
        }
        if (total != null) {
            total.requested(refused);
        }
    }

    void snapshot(boolean tooLarge, int bytes) {
        snapshots++;
        if (tooLarge) {
            this.tooLarge++;
        }
        this.bytes += bytes;
        if (total != null) {
            total.snapshot(tooLarge, bytes);
        }
    }

    /** As {@code key=value} pairs of plain numbers, for the summary lines tools read. */
    void appendTo(StringBuilder out) {
        out.append(" checks=").append(checks).append(" mismatches=").append(mismatches)
                .append(" frames=").append(frames).append(" entries=").append(entries).append(" gone=").append(gone)
                .append(" requested=").append(requested).append(" refused=").append(refused)
                .append(" snapshotsSent=").append(snapshots).append(" tooLarge=").append(tooLarge)
                .append(" bytesOut=").append(bytes);
    }
}
