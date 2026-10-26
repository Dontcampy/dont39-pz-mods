package com.dont39.pz.serverreborn.network.client

/** What the client did this session (R9). Main thread only. */
internal class ClientCounters {
  var frames = 0L
  var entries = 0L
  var checks = 0L
  var mismatches = 0L
  var requested = 0L
  var snapshotsApplied = 0L
  var repaired = 0L
  var unrepairable = 0L
  var pendingUnresolved = 0L
  var notices = 0L
  var openChecks = 0L
  var droppedDeltas = 0L
  var deferred = 0L
  var stuckItems = 0L

  /**
   * As `key=value` pairs of plain numbers, for the summary lines tools read. `pendingUnresolved`: frame entries dropped
   * after their object stayed missing; `waiting`: the ones still retried now; `deferred`: entries that waited out a
   * grace period, `graceWaiting` of them now; `stuckItems`: items a snapshot could not make equal.
   */
  fun appendTo(out: StringBuilder, waiting: Int, graceWaiting: Int) {
    out.append(" frames=").append(frames).append(" entries=").append(entries).append(" checks=").append(checks)
      .append(" mismatches=").append(mismatches).append(" requested=").append(requested)
      .append(" snapshotsApplied=").append(snapshotsApplied).append(" repaired=").append(repaired)
      .append(" unrepairable=").append(unrepairable).append(" pendingUnresolved=").append(pendingUnresolved)
      .append(" waiting=").append(waiting).append(" notices=").append(notices)
      .append(" openChecks=").append(openChecks).append(" droppedDeltas=").append(droppedDeltas)
      .append(" deferred=").append(deferred).append(" graceWaiting=").append(graceWaiting)
      .append(" stuckItems=").append(stuckItems)
  }
}
