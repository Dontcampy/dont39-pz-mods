package com.dont39.pz.servertickrate

import java.lang.management.ManagementFactory
import zombie.GameTime
import zombie.GameWindow
import zombie.iso.IsoWorld
import zombie.network.GameServer

/** PROTOTYPE measurement: one summary line per 10 s window. */
internal object TickProbe {
  private const val WINDOW_NS = 10_000_000_000L

  private var hz = 0
  private var started = false
  private var windowStart = 0L
  private var tickStart = 0L
  private var lastTickStart = 0L
  private val work = ArrayList<Long>()
  private val gaps = ArrayList<Long>()
  private var cpuStart = 0L
  private var mainCpuStart = 0L
  private var sentStart = 0L

  private val threads = ManagementFactory.getThreadMXBean()
  private val os = ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean

  fun start(hz: Int) {
    this.hz = hz
    started = true
  }

  fun tickStart() {
    if (!started) return
    val now = System.nanoTime()
    if (lastTickStart != 0L) gaps += now - lastTickStart
    lastTickStart = now
    tickStart = now
    if (windowStart == 0L) begin(now)
  }

  fun tickEnd() {
    if (!started || tickStart == 0L) return
    val now = System.nanoTime()
    work += now - tickStart
    tickStart = 0L
    if (now - windowStart >= WINDOW_NS) report(now)
  }

  private fun begin(now: Long) {
    windowStart = now
    work.clear()
    gaps.clear()
    cpuStart = os.processCpuTime
    mainCpuStart = threads.currentThreadCpuTime
    sentStart = bytesSent()
  }

  private fun report(now: Long) {
    val secs = (now - windowStart) / 1e9
    val cpu = (os.processCpuTime - cpuStart) / 1e9 / secs
    val mainCpu = (threads.currentThreadCpuTime - mainCpuStart) / 1e9 / secs
    val sent = (bytesSent() - sentStart) / secs
    val w = work.sorted()
    val g = gaps.sorted()
    fun ms(v: Long) = "%.2f".format(v / 1e6)
    fun pct(list: List<Long>, p: Double) = list[((list.size - 1) * p).toInt()]
    val cell = IsoWorld.instance?.currentCell
    ServerTickRate.log {
      "probe hz=$hz ticks=${w.size} tps=${"%.2f".format(w.size / secs)} " +
        "work_ms mean=${ms(w.average().toLong())} p50=${ms(pct(w, 0.5))} p95=${ms(pct(w, 0.95))} max=${ms(w.last())} " +
        "gap_ms mean=${ms(g.average().toLong())} max=${ms(g.last())} " +
        "cpu_proc=${"%.3f".format(cpu)} cpu_main=${"%.3f".format(mainCpu)} sent_Bps=${sent.toLong()} " +
        "players=${GameServer.getPlayerCount()} zombies=${cell?.zombieList?.size ?: -1} " +
        "objects=${cell?.objectList?.size ?: -1} vehicles=${cell?.vehicles?.size ?: -1} " +
        "avgFps=${"%.2f".format(GameWindow.averageFPS)} mult=${"%.3f".format(GameTime.getInstance().multiplier)}"
    }
    begin(now)
  }

  private fun bytesSent(): Long =
    GameServer.udpEngine?.connections?.sumOf { it.statistics?.totalActualBytesSent ?: 0L } ?: 0L
}
