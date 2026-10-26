package com.dont39.pz.serverreborn.networksyncoptimization.client

import com.dont39.pz.serverreborn.ServerReborn
import com.dont39.pz.serverreborn.ServerRebornGate
import com.dont39.pz.serverreborn.networksyncoptimization.game.Containers
import com.dont39.pz.serverreborn.networksyncoptimization.game.ItemDigests
import com.dont39.pz.serverreborn.networksyncoptimization.game.Net
import com.dont39.pz.serverreborn.networksyncoptimization.game.PacketScope
import com.dont39.pz.serverreborn.networksyncoptimization.game.Settings
import com.dont39.pz.serverreborn.networksyncoptimization.protocol.Codec
import com.dont39.pz.serverreborn.networksyncoptimization.protocol.ContainerKey
import com.dont39.pz.serverreborn.networksyncoptimization.protocol.Message
import com.dont39.pz.serverreborn.networksyncoptimization.protocol.Message.Snapshot
import com.dont39.pz.serverreborn.networksyncoptimization.protocol.Message.SyncFrame
import com.dont39.pz.serverreborn.networksyncoptimization.protocol.Protocol
import com.dont39.pz.serverreborn.networksyncoptimization.protocol.Trailer
import com.dont39.pz.serverreborn.networksyncoptimization.protocol.WireKey
import zombie.characters.HaloTextHelper
import zombie.characters.IsoPlayer
import zombie.core.Translator
import zombie.core.network.ByteBufferReader
import zombie.core.raknet.UdpConnection
import zombie.gameStates.ConnectToServerState
import zombie.inventory.InventoryItem
import zombie.inventory.ItemContainer
import zombie.network.GameClient
import zombie.network.PacketTypes.PacketType
import zombie.network.ZomboidNetData
import java.util.concurrent.ConcurrentHashMap

/**
 * The client's side: turns on for a join whose server advertised the protocol (R8), checks the server's sync frames
 * against its own state and asks for snapshots of what differs (R5), applies them in place (R6), checks containers the
 * player opens (R7), and tells the player and the log about repairs (R9). Main thread, except [incoming].
 */
internal object ClientSync {
  private const val NOTICE = "UI_Dont39ServerReborn_SyncRepaired"

  // ids listed per repair line
  private const val MAX_IDS = 20

  // this join's: whether the server offers our protocol, and whether the client said Hello
  private var active = false
  private var mainThread: Thread? = null

  // a failure off the main thread or inside a game send: the next frame says Bye
  @Volatile
  private var byePending = false

  // the game's queued packets that are the mod's; ZomboidNetData keeps Object's identity equals
  private val own: MutableSet<ZomboidNetData> = ConcurrentHashMap.newKeySet()

  private var ledger = Ledger()
  private var counters = ClientCounters()
  private var notices = Notices()
  private var diff: FieldDiff? = null
  private var nextSummary = 0L

  /** What one frame or retry pass asks the server. */
  private class Asks {
    val snapshots = mutableListOf<WireKey>()
    val checks = mutableListOf<Message.OpenCheck>()

    fun send() {
      if (snapshots.isNotEmpty()) {
        ClientSync.send(Message.SnapshotRequest(snapshots))
      }
      checks.forEach { ClientSync.send(it) }
    }
  }

  /** Whether this session is checked; the debug drop counts only then. */
  val checking get() = active

  /**
   * At GameClient.sendPlayerConnect RETURN: PlayerConnect is out, so a Hello on its channel follows it. The trailer is
   * read here, not where the game parses the connection-details (a client that enables the mod only with the server's
   * mod list loads it inside the join, after that) nor at ConnectToServerState.exit (the main screen calls it only
   * while it still updates, which ends once the world loads).
   */
  fun playerConnectSent() {
    if (active) return
    reset()
    val target = "${GameClient.ip}:${GameClient.port}"
    // set by each join's enter()
    val join = ConnectToServerState.instance
    if (join == null) {
      ServerReborn.log { "join to $target: inactive, as the game kept no connection-details" }
      return
    }
    // the join's copy is exactly as long as the data, as the game receives connection-details in parts
    when (val decoded = Trailer.decode(join.connectionDetails.bb)) {
      is Trailer.Decoded.None -> {
        ServerReborn.log { "join to $target: inactive, as ${decoded.reason}" }
        return
      }
      is Trailer.Decoded.Offered -> if (decoded.protocol != Protocol.VERSION) {
        ServerReborn.log {
          "join to $target: inactive, as the server speaks protocol ${decoded.protocol} and this version of the mod " +
            "${Protocol.VERSION}; update Server Reborn on the side that is older"
        }
        return
      }
    }
    active = true
    nextSummary = System.currentTimeMillis() + Settings.summarySeconds * 1000L
    PacketScope.apply()
    val hello = Message.Hello(ClientSync::class.java.`package`.implementationVersion ?: "unknown")
    Net.send(GameClient.connection, Protocol.CONNECT_CHANNEL) { Codec.encode(hello, it) }
    ServerReborn.log { "active: the server checks this client's containers and items" }
  }

  /**
   * At ZomboidNetData.read HEAD, on the network thread, also while the hooks are shut: true when the packet is the
   * mod's, which the game then queues with no type, in arrival order with the in-scope packets. Its data is filled as
   * read does, without the unknown-id error, only while the hooks are open; the game drops it otherwise.
   */
  fun incoming(data: ZomboidNetData, id: Short, input: ByteBufferReader?, connection: UdpConnection?): Boolean {
    if (id != Protocol.PACKET_ID) return false
    data.type = null
    if (ServerRebornGate.active()) {
      data.connection = connection!!.connectedGUID
      data.buffer.put(input!!.bb)
      data.buffer.flip()
      own.add(data)
    }
    return true
  }

  /** At GameClient.mainLoopDealWithNetData HEAD; the game then drops the packet, which has no type. */
  fun dispatch(data: ZomboidNetData) {
    // the game pools its packets: a mark must not outlive this one, and a typed instance carries a stale mark of one
    // the game dropped unrun
    if (!own.remove(data) || data.type != null || !active) return
    when (val m = Codec.decode(data.buffer.bb.duplicate())) {
      is SyncFrame -> frame(m)
      is Snapshot -> snapshot(m)
      else -> throw IllegalStateException("the server sent a ${m.javaClass.simpleName}, which only clients send")
    }
  }

  /**
   * At PacketType.onClientPacket HEAD, for every packet: true to drop it (`-Dserverreborn.debugDropDelta`,
   * `-Dserverreborn.debugDropModule`).
   */
  fun dropDelta(type: PacketType, input: ByteBufferReader?): Boolean = DeltaDrop.drop(type, input)

  fun dropped() {
    counters.droppedDeltas++
  }

  // ---- frames (R5)

  private fun frame(frame: SyncFrame) {
    counters.frames++
    counters.entries += frame.entries.size
    val now = System.currentTimeMillis()
    val asks = Asks()
    frame.entries.forEach { e ->
      if (e.changed || ledger.isWaiting(e.key)) {
        counters.deferred++
        ledger.defer(e.key, e.gone, e.digest, frame.version, now)?.let { check(it, now, asks) }
      } else {
        check(e.key, e.gone, e.digest, frame.version, now, asks)
      }
    }
    asks.send()
  }

  private fun check(w: Ledger.Waiting, now: Long, asks: Asks) = check(w.key, w.gone, w.digest, w.version, now, asks)

  /** One entry against the client's state; a [gone] entry: the server no longer finds the object there. */
  private fun check(wire: WireKey, gone: Boolean, digest: Long, version: Long, now: Long, asks: Asks) {
    if (ledger.superseded(wire, version) || ledger.givenUp(wire)) {
      ledger.resolved(wire)
      return
    }
    val found = Lookup.find(wire, GameClient.connection)
    val container = found.container
    if (container == null) {
      if (gone) {
        // neither side has it
        ledger.resolved(wire)
      } else {
        ledger.unresolved(wire, false, digest, version, now, found.name(wire))
      }
      return
    }
    ledger.resolved(wire)
    counters.checks++
    if (!wire.isItem) {
      // a container the server no longer addresses there: its parent's own entry covers the move
      if (!gone) {
        val mine = containerDigest(container)
        if (mine != digest) {
          mismatch(wire, found.name(wire), container.getItems(), mine, digest, now, asks)
        }
      }
      return
    }
    val item = container.getItemWithID(wire.itemId)
    if (gone) {
      if (item != null) {
        // the server cannot snapshot an item it no longer has there: it compares the whole container
        val whole = WireKey.container(wire.containerBytes)
        val mine = containerDigest(container)
        counters.mismatches++
        if (ledger.ask(whole, now)) {
          counters.requested++
          diff?.mismatch(whole, found.name(whole), container.getItems(), mine, 0)
          asks.checks += Message.OpenCheck(whole, mine)
        }
      }
      return
    }
    val mine = if (item == null) 0 else ledger.digestOf(item.getID(), ItemDigests.item(item))
    if (item == null || mine != digest) {
      mismatch(wire, found.name(wire), listOfNotNull(item), mine, digest, now, asks)
    }
  }

  // items a snapshot could not make equal count as the server has them, while they stay as it left them
  private fun containerDigest(container: ItemContainer) = ItemDigests.container(container, ledger::digestOf)

  private fun mismatch(
    wire: WireKey,
    name: String,
    items: Collection<InventoryItem>,
    mine: Long,
    server: Long,
    now: Long,
    asks: Asks,
  ) {
    counters.mismatches++
    if (!ledger.ask(wire, now)) return
    counters.requested++
    diff?.mismatch(wire, name, items, mine, server)
    asks.snapshots += wire
  }

  // ---- snapshots (R6)

  private fun snapshot(s: Snapshot) {
    val wire = s.key
    ledger.answered(wire)
    when (s.state) {
      Snapshot.State.TOO_LARGE -> {
        ledger.giveUp(wire)
        counters.unrepairable++
        ServerReborn.warn {
          "${Lookup.find(wire, GameClient.connection).name(wire)} is too large for one snapshot packet; not " +
            "repaired, and not asked for again this session"
        }
      }
      Snapshot.State.GONE -> ServerReborn.debug {
        "snapshot of ${Lookup.find(wire, GameClient.connection).name(wire)}: the server no longer has it there"
      }
      Snapshot.State.PRESENT -> applied(s, Reconcile.apply(s, GameClient.connection, diff))
    }
  }

  private fun applied(s: Snapshot, r: Reconcile.Result) {
    val wire = s.key
    when (r.outcome) {
      Reconcile.Outcome.NOT_FOUND -> {
        ServerReborn.debug { "snapshot of ${r.name}: no longer on this client; not applied" }
        return
      }
      Reconcile.Outcome.MATCHED -> {
        counters.snapshotsApplied++
        ledger.applied(wire, s.version)
        stuck(r)
        ServerReborn.debug { "snapshot of ${r.name}: nothing to change, the client had caught up" }
        return
      }
      Reconcile.Outcome.REPAIRED -> {
        counters.snapshotsApplied++
        counters.repaired++
        ledger.applied(wire, s.version)
        ServerReborn.log { "repaired ${r.name} from the server's state:${ids(r)}" }
        stuck(r)
      }
      Reconcile.Outcome.UNREPAIRABLE -> {
        counters.unrepairable++
        ledger.giveUp(wire)
        val line = "could not repair ${r.name}: ${r.failure}; not asked for again this session.${ids(r)}"
        val cause = r.cause
        if (cause != null) {
          ServerReborn.error(line, cause)
        } else {
          ServerReborn.warn { line }
        }
      }
    }
    if (r.changedAnything) {
      notices.repaired()
      notice(System.currentTimeMillis())
    }
  }

  /**
   * Items the snapshot could not make equal: compared as the server has them while they stay as it left them, so the
   * rest of their container is still checked. Logged once per item.
   */
  private fun stuck(r: Reconcile.Result) {
    val first = r.stuck.filter { ledger.accept(it.id, it.mine, it.server) }.map { it.id }
    if (first.isEmpty()) return
    counters.stuckItems += first.size
    ServerReborn.warn {
      "items ${list(first)} in ${r.name} still differ from the server's after its snapshot, in fields a snapshot " +
        "cannot set; until either side changes them they are compared as the server has them"
    }
  }

  private fun ids(r: Reconcile.Result) = (if (r.added.isEmpty()) "" else " added ${list(r.added)}") +
    (if (r.removed.isEmpty()) "" else " removed ${list(r.removed)}") +
    (if (r.changed.isEmpty()) "" else " changed ${list(r.changed)}")

  private fun list(ids: List<Int>) =
    if (ids.size <= MAX_IDS) ids.toString() else "${ids.subList(0, MAX_IDS)} and ${ids.size - MAX_IDS} more"

  // ---- open checks (R7)

  /** At ItemContainer.requestServerItemsForContainer HEAD: the game fills the container; checked once it did. */
  fun itemsRequested(container: ItemContainer) {
    openKey(container)?.let { ledger.filling(WireKey.container(it.wire), System.currentTimeMillis()) }
  }

  /**
   * From the loot window's Lua: the container it shows now. One the game is still filling waits for that; the game
   * cannot ask the server for a nested bag's items, so an unexplored one is checked at once.
   */
  fun containerShown(container: ItemContainer) {
    val at = openKey(container) ?: return
    if (!container.isExplored && at.key.kind != ContainerKey.Kind.NESTED) return
    val wire = WireKey.container(at.wire)
    if (ledger.open(wire, System.currentTimeMillis())) {
      openCheck(wire, container)
    }
  }

  // null when not checked: inactive, or a container without an address (floor, out of the world)
  private fun openKey(container: ItemContainer) = if (active) Containers.current(container) else null

  private fun openCheck(wire: WireKey, container: ItemContainer) {
    counters.openChecks++
    send(Message.OpenCheck(wire, containerDigest(container)))
  }

  // ---- every frame

  /**
   * At GameClient.update RETURN, also while the hooks are shut: says a pending Bye. Then, while checking, retries
   * entries not found yet, runs open checks that waited, shows a due notice and writes the summary.
   */
  fun tick() {
    mainThread = Thread.currentThread()
    if (byePending) {
      byePending = false
      bye()
    }
    if (!active) return
    val now = System.currentTimeMillis()
    val retry = mutableListOf<Ledger.Unresolved>()
    val expired = mutableListOf<Ledger.Unresolved>()
    if (ledger.retry(now, retry, expired)) {
      val asks = Asks()
      retry.forEach { check(it.key, it.gone, it.digest, it.version, now, asks) }
      ledger.due(now).forEach { check(it, now, asks) }
      asks.send()
      expiredLine(expired)
      ledger.filled(now).forEach { wire ->
        Lookup.find(wire, GameClient.connection).container?.let { openCheck(wire, it) }
      }
    }
    notice(now)
    if (now >= nextSummary) {
      nextSummary = now + Settings.summarySeconds * 1000L
      summary()
    }
  }

  private fun expiredLine(expired: List<Ledger.Unresolved>) {
    if (expired.isEmpty()) return
    counters.pendingUnresolved += expired.size
    ServerReborn.log {
      val names = expired.take(MAX_IDS).joinToString("; ") { it.name }
      "${expired.size} objects the server checked were not found on this client within " +
        "${Ledger.UNRESOLVED_MS / 1000} s and are not checked: $names" +
        if (expired.size > MAX_IDS) "; and ${expired.size - MAX_IDS} more" else ""
    }
  }

  // ---- notice and summary (R9)

  private fun notice(now: Long) {
    val player = IsoPlayer.getInstance() ?: return
    val repairs = notices.due(now)
    if (repairs > 0) {
      counters.notices++
      HaloTextHelper.addText(player, Translator.getText(NOTICE, repairs))
      ServerReborn.log { "repair notice shown for $repairs repairs" }
    }
  }

  /** One line of `key=value` numbers, as the server's, for the load-test tools. */
  private fun summary() {
    val line = StringBuilder("scope=client")
    counters.appendTo(line, ledger.unresolvedCount, ledger.waitingCount)
    ServerReborn.log { line.toString() }
  }

  // ---- session end

  /** At IngameState.exit HEAD: ends the server's session. */
  fun leave() {
    if (active) {
      val c = GameClient.connection
      if (c != null && GameClient.instance.connected && Net.canSend(c)) {
        bye()
      }
      summary()
      ServerReborn.log { "left the server" }
    }
    reset()
  }

  // before each join and after leaving
  private fun reset() {
    active = false
    byePending = false
    // marks of packets the game dropped without running them (a lost connection)
    own.clear()
    PacketScope.restore()
    ledger = Ledger()
    counters = ClientCounters()
    notices = Notices()
    diff = if (Settings.fieldDiff) FieldDiff() else null
  }

  /** Undoes what the client changed in the game, after a failure; the hooks are shut already. */
  fun stop() {
    val was = active
    active = false
    own.clear()
    PacketScope.restore()
    if (!was) return
    summary()
    val c = GameClient.connection
    if (Thread.currentThread() === mainThread && c != null && Net.canSend(c)) {
      bye()
    } else {
      byePending = true
    }
  }

  // the server stops sending to this client
  private fun bye() {
    GameClient.connection?.let { c -> Net.send(c, Protocol.SHARED_CHANNEL) { Codec.encode(Message.Bye, it) } }
  }

  private fun send(m: Message) {
    Net.send(GameClient.connection, Protocol.SHARED_CHANNEL) { Codec.encode(m, it) }
  }
}
