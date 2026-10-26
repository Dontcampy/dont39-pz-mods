package com.dont39.pz.serverreborn.network.server

import com.dont39.pz.serverreborn.ServerLoop
import com.dont39.pz.serverreborn.ServerReborn
import com.dont39.pz.serverreborn.network.game.Net
import com.dont39.pz.serverreborn.network.game.PacketScope
import com.dont39.pz.serverreborn.network.protocol.Codec
import com.dont39.pz.serverreborn.network.protocol.Message
import com.dont39.pz.serverreborn.network.protocol.Protocol
import com.dont39.pz.serverreborn.network.protocol.ProtocolException
import com.dont39.pz.serverreborn.network.protocol.Trailer
import zombie.core.network.ByteBufferReader
import zombie.core.network.ByteBufferWriter
import zombie.core.raknet.UdpConnection
import zombie.network.GameServer
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * The server's side (R1, R3, R4, R6, R7): its lifecycle, the connections it checks, the mod's own packets and the
 * end-of-tick phase. [Touches] records what each connection was sent or sent, [Frames] sends the digests of it,
 * [Requests] and [Snapshots] answer the clients. Everything but [incoming] runs on the server's main thread.
 */
internal object ServerSync {
  /** A packet of the mod as the network thread took it: the bytes after the header. */
  private class Inbound(val payload: ByteArray, val connectionGuid: Long)

  private const val SWEEP_NANOS = 1_000_000_000L

  /**
   * Entries no frame covered for this many frames (at least 5 minutes, as frames come at most 10 times per second) go:
   * a client asks within seconds of a frame, and the player's own items never leave its relevance.
   */
  private const val STALE_FRAMES = 3000L

  @Volatile
  private var running = false

  // stop() ran on any thread; the main thread drops its own state at the next tick's start
  @Volatile
  private var stopped = false

  // the mod's packets: filled on the network thread, taken at a main-loop pass's start, run at the next one's
  private val inbound = ConcurrentLinkedQueue<Inbound>()
  private val due = ConcurrentLinkedQueue<Inbound>()

  /** The connections that sent the mod's packets. */
  val peers = HashMap<UdpConnection, Peer>()

  /** Whether any connection is checked: changes are recorded only then. */
  var anyActive = false
    private set

  private var nextSweep = 0L

  /** Whether the server checks: started, and not stopped after a failure. */
  val isRunning get() = running

  /** On a dedicated server, once the gate decided to open. */
  fun start() {
    PacketScope.apply()
    val now = System.nanoTime()
    nextSweep = now + SWEEP_NANOS
    ServerSummary.start(now)
    running = true
  }

  /** Undoes [start] after a failure, on whichever thread failed; the hooks are shut already. */
  fun stop() {
    running = false
    inbound.clear()
    due.clear()
    PacketScope.restore()
    stopped = true
  }

  /** At each tick's start, also while the hooks are shut: drops the main thread's state once [stop] ran. */
  fun tickStarts() {
    if (!stopped) return
    stopped = false
    peers.clear()
    anyActive = false
    Touches.clear()
    Frames.clear()
  }

  // ---- activation (R8)

  /** At ConnectionDetails.write RETURN: advertises the protocol to every joining client. */
  fun detailsWritten(out: ByteBufferWriter?) {
    if (running) {
      Trailer.append(out!!.bb, Protocol.VERSION)
    }
  }

  /** At GameServer.disconnect HEAD. */
  fun disconnected(connection: UdpConnection?) {
    // the peers are the main thread's; the sweep drops a connection that left
    if (!ServerLoop.onMainThread) return
    val p = peers.remove(connection)
    if (p != null && p.active) {
      ServerSummary.connection(p)
    }
  }

  /** The checked peer of [c] on the main thread, or null; throws for an in-scope packet on another thread. */
  fun activePeer(c: UdpConnection?): Peer? {
    if (!ServerLoop.onMainThread) {
      // before the first tick no connection is checked yet
      if (!ServerLoop.ticking) return null
      throw IllegalStateException(
        "an in-scope packet passed on thread ${Thread.currentThread().name}, not the server's main thread; this " +
          "version of the mod tracks only main-thread sends",
      )
    }
    return peers[c]?.takeIf { it.active }
  }

  // ---- own packets (S1)

  /**
   * At GameServer.addIncoming HEAD, on the network thread, also while the hooks are shut: true when the packet is the
   * mod's, which is queued only while the server checks.
   */
  fun incoming(id: Short, input: ByteBufferReader?, connection: UdpConnection?): Boolean {
    if (id != Protocol.PACKET_ID) return false
    if (running && ServerReborn.active()) {
      inbound.add(Inbound(Net.payload(input!!), connection!!.connectedGUID))
    }
    return true
  }

  /**
   * At the start of each pass of the server's main loop, ticking or not, before it runs the game's queued packets. Runs
   * what arrived before the previous pass started: the game ran every packet that arrived before those in that pass,
   * so a Hello comes after its client's PlayerConnect.
   */
  fun passStarts() {
    if (!running) return
    generateSequence { due.poll() }.forEach(::dispatch)
    generateSequence { inbound.poll() }.forEach(due::add)
  }

  private fun dispatch(own: Inbound) {
    if (!running) return
    val c = GameServer.udpEngine.getActiveConnection(own.connectionGuid) ?: return
    val p = peers.getOrPut(c) { Peer(c, ServerSummary.total) }
    if (p.stopped != null) return
    try {
      when (val m = Codec.decode(ByteBuffer.wrap(own.payload))) {
        is Message.Hello -> hello(p, m)
        Message.Bye -> bye(p)
        else -> when {
          !p.active -> throw ProtocolException("a ${m.javaClass.simpleName} before its Hello")
          m is Message.SnapshotRequest -> Requests.snapshots(p, m)
          m is Message.OpenCheck -> Requests.openCheck(p, m)
          else -> throw ProtocolException("a ${m.javaClass.simpleName}, which only servers send")
        }
      }
    } catch (e: ProtocolException) {
      p.stop(e.message.orEmpty())
      ServerReborn.warn { "${p.name}: stopped checking its sync, as it sent ${e.message}" }
    }
  }

  private fun hello(p: Peer, h: Message.Hello) {
    if (p.active) throw ProtocolException("a second Hello")
    p.active = true
    anyActive = true
    p.modVersion = h.modVersion
    ServerReborn.log {
      "${p.name} (connection ${p.connection.index}): checking its sync (Server Reborn ${h.modVersion} on the client)"
    }
  }

  // the client failed or left: nothing more goes to it, and what it still sends is ignored
  private fun bye(p: Peer) {
    if (!p.active) return
    p.stop("a Bye")
    ServerReborn.log {
      "${p.name} (connection ${p.connection.index}): stopped checking its sync, as Server Reborn on the client " +
        "stopped for this session"
    }
    ServerSummary.connection(p)
    p.versions.clear()
  }

  // ---- end of tick (R4)

  /** At each world tick's end, after all of its sends. */
  fun tickEnds() {
    if (!running) return
    val start = System.nanoTime()
    anyActive = peers.values.any { it.active }
    val checked = peers.values.filter { it.active && it.connection.isFullyConnected }
    Touches.recordChanges(checked)
    if (Frames.due()) {
      checked.forEach { p ->
        Frames.send(p)
        Snapshots.serve(p)
      }
      Frames.clear()
    }
    if (start >= nextSweep) {
      nextSweep = start + SWEEP_NANOS
      sweep()
    }
    ServerSummary.tickEnded(start, System.nanoTime())
  }

  // ---- relevance (R1)

  private fun sweep() {
    val it = peers.values.iterator()
    while (it.hasNext()) {
      val p = it.next()
      if (!GameServer.udpEngine.connections.contains(p.connection)) {
        it.remove()
        if (p.active) {
          ServerSummary.connection(p)
        }
        continue
      }
      val oldest = p.frame - STALE_FRAMES
      p.versions.entries.removeIf { (key, version) -> version < oldest || !Relevance.relevant(p.connection, key) }
    }
  }
}
