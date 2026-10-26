package com.dont39.pz.serverreborn.network.server

import com.dont39.pz.serverreborn.ServerReborn
import com.dont39.pz.serverreborn.network.game.Settings
import com.dont39.pz.serverreborn.network.stats.Timings

/**
 * The server's summary lines (R9): every `serverreborn.summarySeconds` (default 600) and per connection when it leaves,
 * one line of `key=value` pairs of plain numbers, which the load-test tools parse. Counters run from the server's start
 * (the connection's join); tick times cover the time since the last line. Main thread only.
 */
internal object ServerSummary {
  /** All connections' counters since the server started. */
  val total = Counters(null)

  // the end-of-tick phase since the last summary
  private val tickTimes = Timings()
  private var periodNanos = 0L
  private var next = 0L

  fun start(now: Long) {
    periodNanos = Settings.summarySeconds * 1_000_000_000L
    next = now + periodNanos
  }

  /** Records one end-of-tick phase, and writes the summary when it is due. */
  fun tickEnded(start: Long, end: Long) {
    tickTimes.record(end - start)
    if (end >= next) {
      next = end + periodNanos
      server()
    }
  }

  private fun server() {
    Touches.summary()
    val active = ServerSync.peers.values.filter { it.active }
    val rt = Runtime.getRuntime()
    val line = StringBuilder("scope=server")
    line.append(" connections=").append(active.size).append(" stateEntries=").append(active.sumOf { it.versions.size })
    total.appendTo(line)
    line.append(" ticks=").append(tickTimes.count).append(" tickNsP50=").append(tickTimes.percentileNanos(0.5))
      .append(" tickNsP99=").append(tickTimes.percentileNanos(0.99))
      .append(" tickNsMax=").append(tickTimes.maxNanos)
      .append(" heapUsed=").append(rt.totalMemory() - rt.freeMemory())
    ServerReborn.log { line.toString() }
    tickTimes.reset()
  }

  fun connection(p: Peer) {
    val line = StringBuilder("scope=connection")
    line.append(" connection=").append(p.connection.index).append(" stateEntries=").append(p.versions.size)
    p.session.appendTo(line)
    ServerReborn.log { line.toString() }
  }
}
