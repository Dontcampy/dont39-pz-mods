package com.dont39.pz.tunnel.transport.kcp

import com.dont39.pz.tunnel.TunnelIo
import com.dont39.pz.tunnel.TunnelLog
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.concurrent.thread

/** Ticks its sessions every [periodMillis] on one thread, [name], until [stop]. */
internal class KcpScheduler(private val name: String, private val periodMillis: Long, private val log: TunnelLog) {
  private val sessions = CopyOnWriteArraySet<KcpSession>()

  @Volatile
  private var stopped = false

  fun add(session: KcpSession) {
    sessions += session
  }

  fun remove(session: KcpSession) {
    sessions -= session
  }

  fun start() {
    thread(isDaemon = true, name = "${TunnelIo.THREAD_PREFIX}$name") {
      try {
        while (!stopped) {
          // each session handles its own failures
          sessions.forEach { it.tick() }
          Thread.sleep(periodMillis)
        }
      } catch (t: Throwable) {
        log.error("running the $name", t)
      }
    }
  }

  fun stop() {
    stopped = true
  }

  companion object {
    /** Often enough for KCP's interval and, with FEC on, its flush wait. */
    fun period(params: KcpParams): Long = (
      if (params.parityShards == 0) params.intervalMillis else minOf(params.intervalMillis, params.flushMillis)
      ).toLong()
  }
}
