package com.dont39.pz.syncguard.client;

/** What the client did this session (R9). Main thread only. */
final class ClientCounters {
    long frames;
    long entries;
    long checks;
    long mismatches;
    long requested;
    long snapshotsApplied;
    long repaired;
    long unrepairable;
    long pendingUnresolved;
    long notices;
    long openChecks;
    long droppedDeltas;

    /**
     * As {@code key=value} pairs of plain numbers, for the summary lines tools read. {@code pendingUnresolved}: frame
     * entries dropped after their object stayed missing; {@code waiting}: the ones still retried now.
     */
    void appendTo(StringBuilder out, int waiting) {
        out.append(" frames=").append(frames).append(" entries=").append(entries).append(" checks=").append(checks)
                .append(" mismatches=").append(mismatches).append(" requested=").append(requested)
                .append(" snapshotsApplied=").append(snapshotsApplied).append(" repaired=").append(repaired)
                .append(" unrepairable=").append(unrepairable).append(" pendingUnresolved=").append(pendingUnresolved)
                .append(" waiting=").append(waiting).append(" notices=").append(notices)
                .append(" openChecks=").append(openChecks).append(" droppedDeltas=").append(droppedDeltas);
    }
}
