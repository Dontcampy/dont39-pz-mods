package com.dont39.pz.syncguard.client;

import com.dont39.pz.syncguard.protocol.WireKey;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the client remembers per object for one session (R5, R6, R7): snapshots asked for and applied, objects given
 * up on, entries whose object is not here yet. No game types, so its rules are tested without the game. Times in
 * milliseconds. Main thread only.
 */
final class Ledger {
    /** How long an entry whose object the client cannot find is retried. */
    static final long UNRESOLVED_MS = 10_000;
    /**
     * How often those are retried: the game's lookup logs an error for some missing parents (a bag, a vehicle) on
     * every try, so not each frame.
     */
    static final long RETRY_MS = 500;
    /** A request the server refused or did not answer: asked again at a later mismatch after this. */
    static final long REQUEST_MS = 30_000;
    /** The same container shown again within this is not checked again. */
    static final long OPEN_MS = 10_000;
    /**
     * A container whose items the game asked the server for is checked this much later, once the game's answer is
     * in: checked at once it would differ by the items still on their way.
     */
    static final long FILL_MS = 3_000;

    /** A frame entry whose object the client did not find; {@code since}: when it was first seen. */
    record Unresolved(WireKey key, boolean gone, long digest, long version, long since, String name) {
    }

    // the version of the last snapshot applied per key
    private final Map<WireKey, Long> applied = new HashMap<>();
    // when each pending request was sent
    private final Map<WireKey, Long> requested = new HashMap<>();
    private final Map<WireKey, Long> opened = new HashMap<>();
    // containers to check once the game's own fill is in, with when
    private final Map<WireKey, Long> filling = new LinkedHashMap<>();
    private final Set<WireKey> givenUp = new HashSet<>();
    private final Map<WireKey, Unresolved> unresolved = new LinkedHashMap<>();
    private long nextRetry;

    /** Whether a frame entry is as old as or older than the last snapshot applied for its key. */
    boolean superseded(WireKey key, long version) {
        Long v = applied.get(key);
        return v != null && version <= v;
    }

    /** True when {@code key} is to be asked for now: not asked already, not given up on. */
    boolean ask(WireKey key, long now) {
        return !givenUp.contains(key) && requested.putIfAbsent(key, now) == null;
    }

    /** True when the container shown is to be checked now. */
    boolean open(WireKey key, long now) {
        if (givenUp.contains(key) || requested.containsKey(key)) {
            return false;
        }
        Long last = opened.get(key);
        if (last != null && now - last < OPEN_MS) {
            return false;
        }
        opened.put(key, now);
        return true;
    }

    /** The game asked the server for the container's items: checked after {@link #FILL_MS}, not before. */
    void filling(WireKey key, long now) {
        opened.put(key, now);
        filling.put(key, now + FILL_MS);
    }

    /** The containers whose check is due now; they leave the ledger. */
    List<WireKey> filled(long now) {
        List<WireKey> due = new ArrayList<>();
        for (Iterator<Map.Entry<WireKey, Long>> it = filling.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<WireKey, Long> e = it.next();
            if (now >= e.getValue()) {
                it.remove();
                due.add(e.getKey());
            }
        }
        return due;
    }

    /** A snapshot of {@code key} arrived, whatever its state. */
    void answered(WireKey key) {
        requested.remove(key);
    }

    void applied(WireKey key, long version) {
        applied.merge(key, version, Math::max);
        unresolved.remove(key);
    }

    /** Never asked for again this session. */
    void giveUp(WireKey key) {
        givenUp.add(key);
        requested.remove(key);
        unresolved.remove(key);
    }

    boolean givenUp(WireKey key) {
        return givenUp.contains(key);
    }

    /** Keeps the entry's first sighting and its latest state. */
    void unresolved(WireKey key, boolean gone, long digest, long version, long now, String name) {
        Unresolved before = unresolved.get(key);
        long since = before == null ? now : before.since();
        unresolved.put(key, new Unresolved(key, gone, digest, version, since, before == null ? name : before.name()));
    }

    void resolved(WireKey key) {
        unresolved.remove(key);
    }

    /**
     * Whether a retry pass is due; then the entries to check again come back in {@code retry} and those past
     * {@link #UNRESOLVED_MS} leave the ledger through {@code expired}. Also forgets requests past {@link #REQUEST_MS}.
     */
    boolean retry(long now, List<Unresolved> retry, List<Unresolved> expired) {
        if (now < nextRetry) {
            return false;
        }
        nextRetry = now + RETRY_MS;
        requested.values().removeIf(at -> now - at >= REQUEST_MS);
        opened.values().removeIf(at -> now - at >= OPEN_MS);
        for (Iterator<Unresolved> it = unresolved.values().iterator(); it.hasNext(); ) {
            Unresolved u = it.next();
            if (now - u.since() >= UNRESOLVED_MS) {
                it.remove();
                expired.add(u);
            } else {
                retry.add(u);
            }
        }
        return true;
    }

    int unresolvedCount() {
        return unresolved.size();
    }

    int requestedCount() {
        return requested.size();
    }
}
