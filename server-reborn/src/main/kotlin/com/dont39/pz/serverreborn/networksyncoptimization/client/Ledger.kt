package com.dont39.pz.serverreborn.networksyncoptimization.client

import com.dont39.pz.serverreborn.networksyncoptimization.protocol.WireKey

/**
 * What the client remembers per object for one session (R5, R6, R7): snapshots asked for and applied, objects given
 * up on, entries whose object is not here yet, entries waiting out their grace period, items a snapshot could not
 * make equal. No game types, so its rules are tested without the game. Times in milliseconds. Main thread only.
 */
internal class Ledger {
  /** A frame entry waiting out its grace period; [since]: when its key's first entry came. */
  data class Waiting(
    val key: WireKey,
    val gone: Boolean,
    val digest: Long,
    val version: Long,
    val since: Long,
    val due: Long,
  )

  /** A frame entry whose object the client did not find; [since]: when it was first seen. */
  data class Unresolved(
    val key: WireKey,
    val gone: Boolean,
    val digest: Long,
    val version: Long,
    val since: Long,
    val name: String,
  )

  /** An item a snapshot could not make equal: the client's digest after it, and the server's. */
  private data class Accepted(val mine: Long, val server: Long)

  // the version of the last snapshot applied per key
  private val applied = HashMap<WireKey, Long>()

  // when each pending request was sent
  private val requested = HashMap<WireKey, Long>()
  private val opened = HashMap<WireKey, Long>()

  // containers to check once the game's own fill is in, with when
  private val filling = LinkedHashMap<WireKey, Long>()
  private val givenUp = HashSet<WireKey>()
  private val unresolved = LinkedHashMap<WireKey, Unresolved>()

  // by when the key was last deferred, the longest-waiting first
  private val waiting = LinkedHashMap<WireKey, Waiting>()
  private val accepted = LinkedHashMap<Int, Accepted>()
  private var nextRetry = 0L

  val waitingCount: Int get() = waiting.size
  val unresolvedCount: Int get() = unresolved.size
  val requestedCount: Int get() = requested.size

  /** Whether a frame entry is as old as or older than the last snapshot applied for its key. */
  fun superseded(key: WireKey, version: Long): Boolean = applied[key]?.let { version <= it } ?: false

  /** True when [key] is to be asked for now: not asked already, not given up on. */
  fun ask(key: WireKey, now: Long): Boolean = key !in givenUp && requested.putIfAbsent(key, now) == null

  /** True when the container shown is to be checked now. */
  fun open(key: WireKey, now: Long): Boolean {
    if (key in givenUp || key in requested) {
      return false
    }
    val last = opened[key]
    if (last != null && now - last < OPEN_MS) {
      return false
    }
    opened[key] = now
    return true
  }

  /** The game asked the server for the container's items: checked after [FILL_MS], not before. */
  fun filling(key: WireKey, now: Long) {
    opened[key] = now
    filling[key] = now + FILL_MS
  }

  /** The containers whose check is due now; they leave the ledger. */
  fun filled(now: Long): List<WireKey> {
    val due = filling.filterValues { now >= it }.keys.toList()
    due.forEach(filling::remove)
    return due
  }

  /** A snapshot of [key] arrived, whatever its state. */
  fun answered(key: WireKey) {
    requested.remove(key)
  }

  fun applied(key: WireKey, version: Long) {
    applied.merge(key, version) { a, b -> maxOf(a, b) }
    unresolved.remove(key)
  }

  /** Never asked for again this session. */
  fun giveUp(key: WireKey) {
    givenUp.add(key)
    requested.remove(key)
    unresolved.remove(key)
    waiting.remove(key)
  }

  fun givenUp(key: WireKey): Boolean = key in givenUp

  /** Keeps the entry's first sighting and its latest state. */
  fun unresolved(key: WireKey, gone: Boolean, digest: Long, version: Long, now: Long, name: String) {
    val before = unresolved[key]
    unresolved[key] = Unresolved(key, gone, digest, version, before?.since ?: now, before?.name ?: name)
  }

  fun resolved(key: WireKey) {
    unresolved.remove(key)
  }

  /**
   * Whether a retry pass is due; then the entries to check again come back in [retry] and those past [UNRESOLVED_MS]
   * leave the ledger through [expired]. Also forgets requests past [REQUEST_MS].
   */
  fun retry(now: Long, retry: MutableList<Unresolved>, expired: MutableList<Unresolved>): Boolean {
    if (now < nextRetry) {
      return false
    }
    nextRetry = now + RETRY_MS
    requested.values.removeIf { now - it >= REQUEST_MS }
    opened.values.removeIf { now - it >= OPEN_MS }
    val (old, young) = unresolved.values.partition { now - it.since >= UNRESOLVED_MS }
    old.forEach { unresolved.remove(it.key) }
    expired += old
    retry += young
    return true
  }

  /** Whether an entry for [key] is waiting: a newer one waits too, or it would be compared first. */
  fun isWaiting(key: WireKey): Boolean = key in waiting

  /**
   * Keeps the newest entry per key until its grace ends. Returns the longest-waiting entry when the bound pushed it
   * out, to compare now, else null.
   */
  fun defer(key: WireKey, gone: Boolean, digest: Long, version: Long, now: Long): Waiting? {
    val since = waiting.remove(key)?.since ?: now
    waiting[key] = Waiting(key, gone, digest, version, since, minOf(now + GRACE_MS, since + GRACE_MAX_MS))
    if (waiting.size <= MAX_WAITING) {
      return null
    }
    val oldest = waiting.values.first()
    waiting.remove(oldest.key)
    return oldest
  }

  /** The entries whose grace ended by [now]; they leave the ledger. */
  fun due(now: Long): List<Waiting> {
    val due = waiting.values.filter { now >= it.due }
    due.forEach { waiting.remove(it.key) }
    return due
  }

  /**
   * A snapshot left item [itemId] at digest [mine] where the server has [server]: its fields hold something a snapshot
   * cannot repair. True the first time for the item this session.
   */
  fun accept(itemId: Int, mine: Long, server: Long): Boolean {
    val first = accepted.remove(itemId) == null
    accepted[itemId] = Accepted(mine, server)
    if (accepted.size > MAX_ACCEPTED) {
      accepted.remove(accepted.keys.first())
    }
    return first
  }

  /**
   * The digest to compare for item [itemId] whose own digest is [own]: the server's from the snapshot that left it
   * differing, while the item is as that snapshot left it; its own otherwise.
   */
  fun digestOf(itemId: Int, own: Long): Long = accepted[itemId]?.takeIf { it.mine == own }?.server ?: own

  companion object {
    /** How long an entry whose object the client cannot find is retried. */
    const val UNRESOLVED_MS = 10_000L

    /** Not each frame: the game's lookup logs an error for some missing parents (a bag, a vehicle) on every try. */
    const val RETRY_MS = 500L

    /** A request the server refused or did not answer: asked again at a later mismatch after this. */
    const val REQUEST_MS = 30_000L

    /** The same container shown again within this is not checked again. */
    const val OPEN_MS = 10_000L

    /**
     * A container whose items the game asked the server for is checked this much later, once the game's answer is
     * in: checked at once it would differ by the items still on their way.
     */
    const val FILL_MS = 3_000L

    /**
     * A frame entry recorded from a content change waits this long before it is compared: a mod's own sync of the
     * change (a server command, unordered on another channel) may arrive after the frame.
     */
    const val GRACE_MS = 1_500L

    /** Newer entries for the same key restart the wait, up to this long after the first one. */
    const val GRACE_MAX_MS = 6_000L

    /** Entries waiting at once; past it the longest-waiting one is compared at once. */
    const val MAX_WAITING = 4_096

    /** Items whose snapshot left them differing; past it the oldest is forgotten and found again. */
    const val MAX_ACCEPTED = 4_096
  }
}
