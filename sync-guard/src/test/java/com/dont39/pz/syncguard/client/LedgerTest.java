package com.dont39.pz.syncguard.client;

import com.dont39.pz.syncguard.protocol.WireKey;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LedgerTest {
    private static final WireKey A = WireKey.container(new byte[] {1, 2, 3});
    private static final WireKey B = WireKey.item(new byte[] {1, 2, 3}, 42);

    @Test
    void entriesUpToTheAppliedSnapshotAreSuperseded() {
        Ledger l = new Ledger();
        assertFalse(l.superseded(A, 5));
        l.applied(A, 7);
        assertTrue(l.superseded(A, 7));
        assertTrue(l.superseded(A, 3));
        assertFalse(l.superseded(A, 8));
        l.applied(A, 4);
        assertTrue(l.superseded(A, 7), "an older snapshot does not lower the mark");
        assertFalse(l.superseded(B, 1));
    }

    @Test
    void aKeyIsAskedOnceUntilAnswered() {
        Ledger l = new Ledger();
        assertTrue(l.ask(A, 0));
        assertFalse(l.ask(A, 10));
        assertTrue(l.ask(B, 10));
        l.answered(A);
        assertTrue(l.ask(A, 20));
    }

    @Test
    void anUnansweredRequestIsForgotten() {
        Ledger l = new Ledger();
        assertTrue(l.ask(A, 0));
        assertTrue(l.ask(B, Ledger.REQUEST_MS - 1));
        assertTrue(l.retry(Ledger.REQUEST_MS, new ArrayList<>(), new ArrayList<>()));
        assertTrue(l.ask(A, Ledger.REQUEST_MS));
        assertFalse(l.ask(B, Ledger.REQUEST_MS), "a younger request stays");
    }

    @Test
    void aKeyGivenUpOnIsNeverAskedAgain() {
        Ledger l = new Ledger();
        assertTrue(l.ask(A, 0));
        l.giveUp(A);
        assertTrue(l.givenUp(A));
        l.answered(A);
        assertFalse(l.ask(A, 1));
        assertFalse(l.open(A, 1));
    }

    @Test
    void unresolvedEntriesAreRetriedThenExpire() {
        Ledger l = new Ledger();
        l.unresolved(A, false, 11, 1, 0, "a");
        l.unresolved(A, false, 12, 2, 4_000, "later name");
        assertEquals(1, l.unresolvedCount());

        List<Ledger.Unresolved> retry = new ArrayList<>();
        List<Ledger.Unresolved> expired = new ArrayList<>();
        assertTrue(l.retry(5_000, retry, expired));
        assertEquals(1, retry.size());
        Ledger.Unresolved u = retry.get(0);
        assertEquals(12, u.digest(), "the latest state is retried");
        assertEquals(2, u.version());
        assertEquals(0, u.since(), "from the first sighting");
        assertEquals("a", u.name());
        assertTrue(expired.isEmpty());

        retry.clear();
        assertFalse(l.retry(5_000 + Ledger.RETRY_MS - 1, retry, expired), "not before the retry interval");
        assertTrue(l.retry(Ledger.UNRESOLVED_MS, retry, expired));
        assertTrue(retry.isEmpty());
        assertEquals(List.of(u), expired);
        assertEquals(0, l.unresolvedCount());
    }

    @Test
    void aResolvedOrAppliedEntryLeaves() {
        Ledger l = new Ledger();
        l.unresolved(A, false, 1, 1, 0, "a");
        l.unresolved(B, false, 1, 1, 0, "b");
        l.resolved(A);
        l.applied(B, 1);
        assertEquals(0, l.unresolvedCount());
    }

    @Test
    void anOpenedContainerWaitsOutItsCooldown() {
        Ledger l = new Ledger();
        assertTrue(l.open(A, 0));
        assertFalse(l.open(A, Ledger.OPEN_MS - 1));
        assertTrue(l.open(A, Ledger.OPEN_MS));
        assertTrue(l.ask(B, 0));
        assertFalse(l.open(B, 0), "not while its snapshot is asked for");
    }

    @Test
    void aFillingContainerIsCheckedLater() {
        Ledger l = new Ledger();
        l.filling(A, 1_000);
        assertFalse(l.open(A, 1_500), "shown while the game fills it");
        assertTrue(l.filled(1_000 + Ledger.FILL_MS - 1).isEmpty());
        assertEquals(List.of(A), l.filled(1_000 + Ledger.FILL_MS));
        assertTrue(l.filled(1_000 + Ledger.FILL_MS).isEmpty(), "once");
    }
}
