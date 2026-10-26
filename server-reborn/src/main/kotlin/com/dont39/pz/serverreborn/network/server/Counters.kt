package com.dont39.pz.serverreborn.network.server

/** What the server did since it started, per connection and, adding up, for all of them. Main thread only. */
internal class Counters(
  /** Also counted there; null for the total itself. */
  private val total: Counters?,
) {
  private var frames = 0L
  private var entries = 0L
  private var gone = 0L
  private var changed = 0L
  private var checks = 0L
  private var mismatches = 0L
  private var requested = 0L
  private var refused = 0L
  private var snapshots = 0L
  private var tooLarge = 0L
  private var bytes = 0L

  /** [changed]: entries recorded from a content change (D7). */
  fun frame(entries: Int, gone: Int, changed: Int, bytes: Int) {
    frames++
    this.entries += entries
    this.gone += gone
    this.changed += changed
    this.bytes += bytes
    total?.frame(entries, gone, changed, bytes)
  }

  fun check(differed: Boolean) {
    checks++
    if (differed) mismatches++
    total?.check(differed)
  }

  fun requested(refused: Boolean) {
    requested++
    if (refused) this.refused++
    total?.requested(refused)
  }

  fun snapshot(tooLarge: Boolean, bytes: Int) {
    snapshots++
    if (tooLarge) this.tooLarge++
    this.bytes += bytes
    total?.snapshot(tooLarge, bytes)
  }

  /** As `key=value` pairs of plain numbers, for the summary lines tools read. */
  fun appendTo(out: StringBuilder) {
    out.append(" checks=").append(checks).append(" mismatches=").append(mismatches)
      .append(" frames=").append(frames).append(" entries=").append(entries).append(" gone=").append(gone)
      .append(" changed=").append(changed)
      .append(" requested=").append(requested).append(" refused=").append(refused)
      .append(" snapshotsSent=").append(snapshots).append(" tooLarge=").append(tooLarge)
      .append(" bytesOut=").append(bytes)
  }
}
