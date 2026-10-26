package com.dont39.pz.serverreborn.networksyncoptimization.server

import com.dont39.pz.serverreborn.ServerReborn
import com.dont39.pz.serverreborn.ServerRebornGate
import com.dont39.pz.serverreborn.networksyncoptimization.game.Containers
import com.dont39.pz.serverreborn.networksyncoptimization.game.ItemDigests
import com.dont39.pz.serverreborn.networksyncoptimization.game.Net
import com.dont39.pz.serverreborn.networksyncoptimization.game.PacketScope
import com.dont39.pz.serverreborn.networksyncoptimization.game.Settings
import com.dont39.pz.serverreborn.networksyncoptimization.protocol.Codec
import com.dont39.pz.serverreborn.networksyncoptimization.protocol.ContainerKey
import com.dont39.pz.serverreborn.networksyncoptimization.protocol.ItemKey
import com.dont39.pz.serverreborn.networksyncoptimization.protocol.Key
import com.dont39.pz.serverreborn.networksyncoptimization.protocol.Message
import com.dont39.pz.serverreborn.networksyncoptimization.protocol.Message.SyncFrame
import com.dont39.pz.serverreborn.networksyncoptimization.protocol.Protocol
import com.dont39.pz.serverreborn.networksyncoptimization.protocol.ProtocolException
import com.dont39.pz.serverreborn.networksyncoptimization.protocol.Trailer
import com.dont39.pz.serverreborn.networksyncoptimization.protocol.WireKey
import com.dont39.pz.serverreborn.networksyncoptimization.stats.Timings
import zombie.core.network.ByteBufferReader
import zombie.core.network.ByteBufferWriter
import zombie.core.raknet.UdpConnection
import zombie.inventory.ItemContainer
import zombie.network.GameServer
import zombie.network.IConnection
import zombie.network.PacketTypes.PacketType
import zombie.network.fields.ContainerID
import zombie.network.packets.AddInventoryItemToContainerPacket
import zombie.network.packets.INetworkPacket
import zombie.network.packets.ItemStatsPacket
import zombie.network.packets.RemoveInventoryItemFromContainerPacket
import zombie.network.packets.ReplaceInventoryItemInContainerPacket
import zombie.network.packets.SyncHandWeaponFieldsPacket
import zombie.network.packets.SyncItemFieldsPacket
import zombie.network.packets.SyncItemModDataPacket
import zombie.vehicles.VehicleManager
import java.nio.ByteBuffer
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * The server's side (R1, R3, R4, R6, R7): records which connection was sent or sent which in-scope object during a
 * tick, and which containers' contents changed (D7), and at the tick's end sends each connection one frame with those
 * objects' digests; answers snapshot requests and open checks within a budget. Everything but [incoming] and
 * [contentChanged] runs on the server's main thread.
 */
internal object ServerSync {
  /**
   * One object touched this tick, as the first packet or change that touched it addressed it. [changed]: its contents
   * changed this tick, so the client gives a mod's own sync of it time to land.
   */
  class Touch(val key: Key, val container: ItemContainer, val wire: ByteArray, val changed: Boolean) {
    fun asChanged() = if (changed) this else Touch(key, container, wire, true)
  }

  /** A packet of the mod as the network thread took it: the bytes after the header. */
  private class Inbound(val payload: ByteArray, val connectionGuid: Long)

  const val SNAPSHOTS_PER_TICK = 8
  const val SNAPSHOT_BYTES_PER_TICK = 64 * 1024

  // per connection; more are refused, the client asks again at a later mismatch
  private const val MAX_QUEUED = 1024

  // a frame packet's entries, well within the connection's 1,000,000-byte send buffer
  private const val FRAME_BYTES = 256 * 1024
  private const val SWEEP_NANOS = 1_000_000_000L

  /**
   * Entries no frame covered for this many frames (at least 5 minutes) go: a client asks within seconds of a frame, and
   * the player's own items never leave its relevance.
   */
  private const val STALE_FRAMES = 3000L

  @Volatile
  private var running = false
  private var mainThread: Thread? = null

  // the mod's packets: filled on the network thread, taken at a main-loop pass's start, run at the next one's
  private val inbound = ConcurrentLinkedQueue<Inbound>()
  private val due = ConcurrentLinkedQueue<Inbound>()

  private val peers = HashMap<UdpConnection, Peer>()

  // containers whose items changed on the main thread since the last tick's end (D7)
  private val changed: MutableSet<ItemContainer> = Collections.newSetFromMap(IdentityHashMap())

  // whether any connection is checked: changes are recorded only then
  private var anyActive = false

  // debug only: item changes of containers off the main thread since the last summary, which are not recorded
  private val offThreadChanges = AtomicLong()

  // debug only: packets the game sent since the last summary, by type
  private val sentTypes = HashMap<String, Int>()

  // each touched object's entry, computed once per tick for all connections
  private val digests = HashMap<Key, SyncFrame.Entry>()
  private val total = Counters(null)

  // the end-of-tick phase since the last summary
  private val tickTimes = Timings()
  private var summaryNanos = 0L
  private var nextSweep = 0L
  private var nextSummary = 0L

  // the in-scope packet the game wrote last, paired with the send that follows it
  private var written: INetworkPacket? = null

  /** The mod's own writes with the game's packet classes are not sends. */
  var ownWrite = false

  private val onMainThread get() = Thread.currentThread() === mainThread

  /** On a dedicated server, once the gate decided to open. */
  fun start() {
    PacketScope.apply()
    val now = System.nanoTime()
    nextSweep = now + SWEEP_NANOS
    summaryNanos = Settings.summarySeconds * 1_000_000_000L
    nextSummary = now + summaryNanos
    running = true
  }

  /** Undoes [start] after a failure; the hooks are shut already. */
  fun stop() {
    running = false
    inbound.clear()
    due.clear()
    changed.clear()
    PacketScope.restore()
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
    if (!onMainThread) return
    val p = peers.remove(connection)
    if (p != null && p.active) {
      connectionSummary(p)
    }
  }

  // ---- own packets (S1)

  /**
   * At GameServer.addIncoming HEAD, on the network thread, also while the hooks are shut: true when the packet is the
   * mod's, which is queued only while the server checks.
   */
  fun incoming(id: Short, input: ByteBufferReader?, connection: UdpConnection?): Boolean {
    if (id != Protocol.PACKET_ID) return false
    if (running && ServerRebornGate.active()) {
      inbound.add(Inbound(Net.payload(input!!), connection!!.connectedGUID))
    }
    return true
  }

  /**
   * At PoolCaps.updateServerCaps HEAD: the start of each pass of the server's main loop, ticking or not, before it runs
   * the game's queued packets. Runs what arrived before the previous pass started: the game ran every packet that
   * arrived before those in that pass, so a Hello comes after its client's PlayerConnect.
   */
  fun loopStarts() {
    if (!running) return
    generateSequence { due.poll() }.forEach(::dispatch)
    generateSequence { inbound.poll() }.forEach(due::add)
  }

  private fun dispatch(own: Inbound) {
    if (!running) return
    val c = GameServer.udpEngine.getActiveConnection(own.connectionGuid) ?: return
    val p = peers.getOrPut(c) { Peer(c, total) }
    if (p.stopped != null) return
    try {
      when (val m = Codec.decode(ByteBuffer.wrap(own.payload))) {
        is Message.Hello -> hello(p, m)
        Message.Bye -> bye(p)
        else -> when {
          !p.active -> throw ProtocolException("a ${m.javaClass.simpleName} before its Hello")
          m is Message.SnapshotRequest -> request(p, m)
          m is Message.OpenCheck -> openCheck(p, m)
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
    connectionSummary(p)
    p.versions.clear()
  }

  // ---- touch recording (R3)

  /** At the RETURN of an in-scope packet class's write. */
  fun written(packet: INetworkPacket) {
    if (running && !ownWrite && onMainThread) {
      written = packet
    } else if (Settings.touches && running && !ownWrite) {
      debug {
        "write of ${packet.javaClass.simpleName} on thread ${Thread.currentThread().name}, not the main thread " +
          (mainThread?.name ?: "(no tick yet)")
      }
    }
  }

  /** At PacketType.send HEAD: the packet written last goes to [connection]. Every packet passes here. */
  fun sending(type: PacketType, connection: IConnection?) {
    if (!running) return
    if (Settings.touches && onMainThread) {
      sentTypes.merge(type.name, 1, Int::plus)
    }
    if (!onMainThread) {
      // another thread's send must not take the main thread's packet; an in-scope one is an error
      if (PacketScope.kind(type) != null && connection is UdpConnection) {
        activePeer(connection)
      }
      return
    }
    val packet = written
    if (packet == null) {
      if (Settings.touches && PacketScope.kind(type) != null) {
        debug { "send of ${type.name} with no write seen before it" }
      }
      return
    }
    written = null
    if (PacketScope.kind(type) != null && connection is UdpConnection) {
      val p = activePeer(connection)
      if (p != null) {
        touch(p, packet)
      } else if (Settings.touches) {
        debug { "send of ${type.name} to ${connection.connectedGUID}, not an active connection" }
      }
    }
  }

  /** At PacketType.onServerPacket HEAD: where the packet's data starts, or -1 when it is not of interest. */
  fun receiving(type: PacketType, input: ByteBufferReader?): Int =
    if (running && PacketScope.kind(type) != null) input!!.bb.position() else -1

  /**
   * At PacketType.onServerPacket FINALLY: the sender touched the object whether the game applied, rejected or failed on
   * it. A packet the game read is the connection's cached one; an unauthorized one it did not read.
   */
  fun received(type: PacketType, input: ByteBufferReader?, connection: UdpConnection?, start: Int) {
    if (start < 0) return
    val p = activePeer(connection) ?: return
    if (input!!.bb.position() != start) {
      touch(p, p.connection.getPacket(type))
      return
    }
    val kind = PacketScope.kind(type)
    // these start with the container; the item packets need only a login, which a peer has
    if (kind == PacketScope.Kind.REMOVE || kind == PacketScope.Kind.DELETE) {
      val data = input.bb.duplicate()
      data.position(start)
      val id = ContainerID()
      try {
        id.parse(ByteBufferReader(data), connection)
      } catch (_: RuntimeException) {
        // the client's bytes, which the game would not have read either
        return
      }
      touchContainer(p, id)
    }
  }

  private fun activePeer(c: UdpConnection?): Peer? {
    val t = Thread.currentThread()
    if (t !== mainThread) {
      // before the first tick no connection is checked yet
      if (mainThread == null) return null
      throw IllegalStateException(
        "an in-scope packet passed on thread ${t.name}, not the server's main thread; this version of the mod " +
          "tracks only main-thread sends",
      )
    }
    return peers[c]?.takeIf { it.active }
  }

  private fun touch(p: Peer, packet: INetworkPacket?) {
    when (packet) {
      is AddInventoryItemToContainerPacket -> touchContainer(p, packet.containerId)
      is RemoveInventoryItemFromContainerPacket -> touchContainer(p, packet.containerId)
      is ReplaceInventoryItemInContainerPacket -> touchContainer(p, packet.containerId)
      is SyncItemFieldsPacket -> touchItem(p, packet.containerId, packet.itemId)
      is SyncItemModDataPacket -> touchItem(p, packet.containerId, packet.itemId)
      is SyncHandWeaponFieldsPacket -> touchItem(p, packet.containerId, packet.itemId)
      is ItemStatsPacket -> touchItem(p, packet.containerId, packet.id)
      else -> throw IllegalStateException("an in-scope packet type sent a ${packet?.javaClass?.name}")
    }
  }

  private fun touchContainer(p: Peer, id: ContainerID) {
    val container = id.getContainer()
    val at = container?.let { Containers.of(id) }
    if (Settings.touches) {
      debug { "touch ${id.getContainerType()} ${at?.key}: ${outcome(container, at)}" }
    }
    if (container != null && at != null) {
      p.touched.getOrPut(at.key) { Touch(at.key, container, at.wire, false) }
    }
  }

  private fun touchItem(p: Peer, id: ContainerID, itemId: Int) {
    val container = id.getContainer()
    val at = container?.let { Containers.of(id) }
    if (Settings.touches) {
      debug { "touch item $itemId in ${id.getContainerType()} ${at?.key}: ${outcome(container, at)}" }
    }
    if (container != null && at != null) {
      val key = ItemKey(at.key, itemId)
      p.touched.getOrPut(key) { Touch(key, container, at.wire, false) }
    }
  }

  private fun outcome(container: ItemContainer?, at: Containers.Address?) = when {
    container == null -> "no container"
    at == null -> "not addressed"
    else -> "recorded"
  }

  /**
   * At the HEAD of each ItemContainer method through which items enter or leave a container (D7), on either side and
   * any thread. Only the server's main thread counts: chunk loading builds containers on its own threads, and those
   * reach clients with the chunk.
   */
  fun contentChanged(container: ItemContainer) {
    if (!running || !anyActive) return
    if (onMainThread) {
      changed.add(container)
    } else if (Settings.touches) {
      offThreadChanges.incrementAndGet()
    }
  }

  /**
   * Each container changed this tick, as one touch for every checked connection the game sends that container's
   * changes to; a send of the same key this tick is marked changed too, as the change may also travel another way.
   */
  private fun recordChanges(checked: List<Peer>) {
    changed.forEach { container ->
      val at = Containers.address(container)
      var to = 0
      if (at != null) {
        checked.filter { relevant(it.connection, at.key) }.forEach { p ->
          p.touched.merge(at.key, Touch(at.key, container, at.wire, true)) { sent, _ -> sent.asChanged() }
          to++
        }
      }
      if (Settings.touches) {
        debug {
          "content change of ${at?.key ?: "a container the game does not address"}: recorded for $to connections"
        }
      }
    }
    changed.clear()
  }

  private inline fun debug(message: () -> String) = ServerReborn.log { "debug: ${message()}" }

  // ---- end of tick (R4)

  /** At ServerMap.postupdate HEAD: once per world tick, after all of its sends. */
  fun endOfTick() {
    if (!running) return
    val start = System.nanoTime()
    mainThread = Thread.currentThread()
    digests.clear()
    anyActive = peers.values.any { it.active }
    val checked = peers.values.filter { it.active && it.connection.isFullyConnected }
    if (changed.isNotEmpty()) {
      recordChanges(checked)
    }
    checked.forEach { p ->
      if (p.touched.isNotEmpty()) {
        sendFrame(p)
      }
      Snapshots.serve(p)
    }
    digests.clear()
    if (start >= nextSweep) {
      nextSweep = start + SWEEP_NANOS
      sweep()
    }
    val end = System.nanoTime()
    tickTimes.record(end - start)
    if (end >= nextSummary) {
      nextSummary = end + summaryNanos
      summary()
    }
  }

  private fun sendFrame(p: Peer) {
    val version = ++p.frame
    var gone = 0
    var changes = 0
    val entries = p.touched.values.map { t ->
      var e = digests.getOrPut(t.key) { resolve(t) }
      if (t.changed) {
        e = e.asChanged()
        changes++
      }
      if (e.gone) {
        gone++
        p.versions.remove(t.key)
      } else {
        p.versions[t.key] = version
      }
      e
    }
    p.touched.clear()
    var bytes = 0
    var from = 0
    var size = 0
    entries.forEachIndexed { i, e ->
      size += Codec.size(e)
      if (size > FRAME_BYTES) {
        bytes += sendFrame(p, version, entries.subList(from, i))
        from = i
        size = Codec.size(e)
      }
    }
    bytes += sendFrame(p, version, entries.subList(from, entries.size))
    p.session.frame(entries.size, gone, changes, bytes)
  }

  private fun sendFrame(p: Peer, version: Long, entries: List<SyncFrame.Entry>): Int {
    val frame = SyncFrame(version, entries)
    return Net.send(p.connection, Protocol.SHARED_CHANNEL) { Codec.encode(frame, it) }
  }

  private fun resolve(t: Touch): SyncFrame.Entry {
    val key = t.key
    val wire = if (key is ItemKey) WireKey.item(t.wire, key.itemId) else WireKey.container(t.wire)
    if (!Containers.live(t.container, key.container)) {
      return SyncFrame.Entry(wire, true, 0)
    }
    if (key is ItemKey) {
      val item = t.container.getItemWithID(key.itemId) ?: return SyncFrame.Entry(wire, true, 0)
      return SyncFrame.Entry(wire, false, ItemDigests.item(item))
    }
    return SyncFrame.Entry(wire, false, ItemDigests.container(t.container))
  }

  // ---- requests (R6, R7)

  private fun request(p: Peer, r: Message.SnapshotRequest) {
    r.keys.forEach { wire ->
      val key = key(p, wire)
      val refused = key == null || key !in p.versions || p.queued.size >= MAX_QUEUED
      p.session.requested(refused)
      if (!refused && p.queued.add(key)) {
        p.snapshots.add(Peer.Job(key, wire, false))
      }
    }
  }

  private fun openCheck(p: Peer, o: Message.OpenCheck) {
    val at = parse(p, o.key.containerBytes)
    val key = if (at == null) null else key(o.key)
    if (at == null || key == null || !relevant(p.connection, key)) {
      p.session.requested(true)
      return
    }
    val container = at.container
    var digest: Long? = null
    if (container != null && Containers.live(container, key.container)) {
      digest = if (key is ItemKey) {
        container.getItemWithID(key.itemId)?.let(ItemDigests::item)
      } else {
        ItemDigests.container(container)
      }
    }
    val differed = digest == null || digest != o.digest
    p.session.check(differed)
    p.versions.putIfAbsent(key, p.frame)
    if (differed && p.queued.size < MAX_QUEUED && p.queued.add(key)) {
      p.snapshots.add(Peer.Job(key, o.key, true))
    }
  }

  private fun key(p: Peer, wire: WireKey): Key? = if (parse(p, wire.containerBytes) == null) null else key(wire)

  /** Null for a floor container, and for an address this version of the mod cannot read. */
  private fun key(wire: WireKey): Key? {
    val ck = try {
      Containers.keyOf(wire.containerBytes)
    } catch (_: IllegalStateException) {
      return null
    } ?: return null
    return if (wire.isItem) ItemKey(ck, wire.itemId) else ck
  }

  /**
   * The container a client's bytes address, or null when there is none to look in: the client's garbage, a parent that
   * left (the game's lookup throws for a player who is gone), a nested bag that is gone.
   */
  fun parse(p: Peer, wire: ByteArray): Containers.Resolved? = try {
    Containers.find(wire, p.connection)
  } catch (_: RuntimeException) {
    null
  }

  // ---- relevance (R1)

  private fun sweep() {
    val it = peers.values.iterator()
    while (it.hasNext()) {
      val p = it.next()
      if (!GameServer.udpEngine.connections.contains(p.connection)) {
        it.remove()
        if (p.active) {
          connectionSummary(p)
        }
        continue
      }
      val oldest = p.frame - STALE_FRAMES
      p.versions.entries.removeIf { (key, version) -> version < oldest || !relevant(p.connection, key) }
    }
  }

  /** Whether the game still sends [key]'s changes to [c]. */
  fun relevant(c: UdpConnection, key: Key): Boolean {
    val k = key.container
    return when (k.kind) {
      ContainerKey.Kind.PLAYER, ContainerKey.Kind.BAG -> c.hasPlayer(k.owner.toShort())
      ContainerKey.Kind.VEHICLE, ContainerKey.Kind.VEHICLE_BAG -> {
        val v = VehicleManager.instance.getVehicleByID(k.owner.toShort())
        v != null && c.isRelevantTo(v.x, v.y)
      }
      ContainerKey.Kind.OBJECT, ContainerKey.Kind.OBJECT_CONTAINER, ContainerKey.Kind.CORPSE,
      ContainerKey.Kind.WORLD_ITEM,
      -> c.isRelevantTo(k.x.toFloat(), k.y.toFloat())
      // never only its owner's: an anchor is not a player's
      ContainerKey.Kind.NESTED -> relevant(c, k.anchor!!)
    }
  }

  // ---- summary (R9)

  /**
   * Every `serverreborn.summarySeconds` (default 600) and per connection when it leaves: one line of `key=value` pairs
   * of plain numbers, which the load-test tools parse. Counters run from the server's start (the connection's join);
   * tick times cover the time since the last line.
   */
  private fun summary() {
    if (sentTypes.isNotEmpty()) {
      debug { "sent since the last summary: $sentTypes" }
      sentTypes.clear()
    }
    val offThread = offThreadChanges.getAndSet(0)
    if (offThread > 0) {
      debug { "$offThread item changes of containers off the main thread since the last summary, not recorded" }
    }
    val active = peers.values.filter { it.active }
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

  private fun connectionSummary(p: Peer) {
    val line = StringBuilder("scope=connection")
    line.append(" connection=").append(p.connection.index).append(" stateEntries=").append(p.versions.size)
    p.session.appendTo(line)
    ServerReborn.log { line.toString() }
  }
}
