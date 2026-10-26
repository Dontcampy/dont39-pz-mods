package com.dont39.pz.serverreborn.networksyncoptimization.client

import com.dont39.pz.serverreborn.networksyncoptimization.protocol.WireKey
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LedgerTest {
  private val a = WireKey.container(byteArrayOf(1, 2, 3))
  private val b = WireKey.item(byteArrayOf(1, 2, 3), 42)

  @Test
  fun `entries up to the applied snapshot are superseded`() {
    val l = Ledger()
    assertFalse(l.superseded(a, 5))
    l.applied(a, 7)
    assertTrue(l.superseded(a, 7))
    assertTrue(l.superseded(a, 3))
    assertFalse(l.superseded(a, 8))
    l.applied(a, 4)
    assertTrue(l.superseded(a, 7), "an older snapshot does not lower the mark")
    assertFalse(l.superseded(b, 1))
  }

  @Test
  fun `a key is asked once until answered`() {
    val l = Ledger()
    assertTrue(l.ask(a, 0))
    assertFalse(l.ask(a, 10))
    assertTrue(l.ask(b, 10))
    l.answered(a)
    assertTrue(l.ask(a, 20))
  }

  @Test
  fun `an unanswered request is forgotten`() {
    val l = Ledger()
    assertTrue(l.ask(a, 0))
    assertTrue(l.ask(b, Ledger.REQUEST_MS - 1))
    assertTrue(l.retry(Ledger.REQUEST_MS, mutableListOf(), mutableListOf()))
    assertTrue(l.ask(a, Ledger.REQUEST_MS))
    assertFalse(l.ask(b, Ledger.REQUEST_MS), "a younger request stays")
  }

  @Test
  fun `a key given up on is never asked again`() {
    val l = Ledger()
    assertTrue(l.ask(a, 0))
    l.giveUp(a)
    assertTrue(l.givenUp(a))
    l.answered(a)
    assertFalse(l.ask(a, 1))
    assertFalse(l.open(a, 1))
  }

  @Test
  fun `unresolved entries are retried, then expire`() {
    val l = Ledger()
    l.unresolved(a, false, 11, 1, 0, "a")
    l.unresolved(a, false, 12, 2, 4_000, "later name")
    assertEquals(1, l.unresolvedCount)

    val retry = mutableListOf<Ledger.Unresolved>()
    val expired = mutableListOf<Ledger.Unresolved>()
    assertTrue(l.retry(5_000, retry, expired))
    assertEquals(1, retry.size)
    val u = retry[0]
    assertEquals(12, u.digest, "the latest state is retried")
    assertEquals(2, u.version)
    assertEquals(0, u.since, "from the first sighting")
    assertEquals("a", u.name)
    assertTrue(expired.isEmpty())

    retry.clear()
    assertFalse(l.retry(5_000 + Ledger.RETRY_MS - 1, retry, expired), "not before the retry interval")
    assertTrue(l.retry(Ledger.UNRESOLVED_MS, retry, expired))
    assertTrue(retry.isEmpty())
    assertEquals(listOf(u), expired)
    assertEquals(0, l.unresolvedCount)
  }

  @Test
  fun `a resolved or applied entry leaves`() {
    val l = Ledger()
    l.unresolved(a, false, 1, 1, 0, "a")
    l.unresolved(b, false, 1, 1, 0, "b")
    l.resolved(a)
    l.applied(b, 1)
    assertEquals(0, l.unresolvedCount)
  }

  @Test
  fun `an opened container waits out its cooldown`() {
    val l = Ledger()
    assertTrue(l.open(a, 0))
    assertFalse(l.open(a, Ledger.OPEN_MS - 1))
    assertTrue(l.open(a, Ledger.OPEN_MS))
    assertTrue(l.ask(b, 0))
    assertFalse(l.open(b, 0), "not while its snapshot is asked for")
  }

  @Test
  fun `a changed entry waits out its grace`() {
    val l = Ledger()
    assertNull(l.defer(a, false, 11, 1, 1_000))
    assertTrue(l.isWaiting(a))
    assertTrue(l.due(1_000 + Ledger.GRACE_MS - 1).isEmpty())
    val due = l.due(1_000 + Ledger.GRACE_MS)
    assertEquals(1, due.size)
    assertEquals(11, due[0].digest)
    assertFalse(l.isWaiting(a), "once")
    assertEquals(0, l.waitingCount)
  }

  @Test
  fun `a newer entry replaces the waiting one and restarts its wait`() {
    val l = Ledger()
    l.defer(a, false, 11, 1, 0)
    l.defer(a, true, 0, 2, 1_000)
    assertEquals(1, l.waitingCount)
    assertTrue(l.due(Ledger.GRACE_MS).isEmpty(), "the newer entry's wait counts")
    val due = l.due(1_000 + Ledger.GRACE_MS)
    assertEquals(1, due.size)
    val w = due[0]
    assertEquals(2, w.version, "the newest entry is compared")
    assertTrue(w.gone)
    assertEquals(0, w.since)
  }

  @Test
  fun `a key that keeps changing is still compared`() {
    val l = Ledger()
    for (t in 0 until Ledger.GRACE_MAX_MS step 500) {
      l.defer(a, false, t, t, t)
      assertTrue(l.due(t).isEmpty())
    }
    assertEquals(1, l.due(Ledger.GRACE_MAX_MS).size, "at most GRACE_MAX_MS after the first entry")
  }

  @Test
  fun `waiting entries are bounded`() {
    val l = Ledger()
    repeat(Ledger.MAX_WAITING) {
      assertNull(l.defer(WireKey.item(byteArrayOf(1), it), false, it.toLong(), 1, it.toLong()))
    }
    val out = l.defer(a, false, 1, 1, Ledger.MAX_WAITING.toLong())
    assertEquals(WireKey.item(byteArrayOf(1), 0), out?.key, "the longest-waiting one is compared at once")
    assertEquals(Ledger.MAX_WAITING, l.waitingCount)
  }

  @Test
  fun `a given-up key stops waiting`() {
    val l = Ledger()
    l.defer(a, false, 1, 1, 0)
    l.giveUp(a)
    assertFalse(l.isWaiting(a))
  }

  @Test
  fun `an accepted item counts as the server has it while unchanged`() {
    val l = Ledger()
    assertEquals(5, l.digestOf(42, 5))
    assertTrue(l.accept(42, 5, 9))
    assertEquals(9, l.digestOf(42, 5))
    assertEquals(6, l.digestOf(42, 6), "changed on the client: its own digest again")
    assertEquals(7, l.digestOf(43, 7))
    assertFalse(l.accept(42, 6, 10), "reported once per item")
    assertEquals(10, l.digestOf(42, 6))
    assertEquals(5, l.digestOf(42, 5), "only the latest pair")
  }

  @Test
  fun `accepted items are bounded`() {
    val l = Ledger()
    for (i in 0..Ledger.MAX_ACCEPTED) {
      l.accept(i, 1, 2)
    }
    assertEquals(1, l.digestOf(0, 1), "the oldest is forgotten")
    assertEquals(2, l.digestOf(Ledger.MAX_ACCEPTED, 1))
  }

  @Test
  fun `a filling container is checked later`() {
    val l = Ledger()
    l.filling(a, 1_000)
    assertFalse(l.open(a, 1_500), "shown while the game fills it")
    assertTrue(l.filled(1_000 + Ledger.FILL_MS - 1).isEmpty())
    assertEquals(listOf(a), l.filled(1_000 + Ledger.FILL_MS))
    assertTrue(l.filled(1_000 + Ledger.FILL_MS).isEmpty(), "once")
  }
}
