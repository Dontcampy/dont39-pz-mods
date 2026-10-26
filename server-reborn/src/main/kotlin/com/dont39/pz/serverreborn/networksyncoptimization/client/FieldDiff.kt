package com.dont39.pz.serverreborn.networksyncoptimization.client

import com.dont39.pz.serverreborn.ServerReborn
import com.dont39.pz.serverreborn.networksyncoptimization.game.ItemDigests
import com.dont39.pz.serverreborn.networksyncoptimization.protocol.WireKey
import zombie.inventory.InventoryItem

/**
 * `-Dserverreborn.debugFieldDiff`: which digest fields differ per item, for the in-game run that settles
 * `ItemDigests.Checked`. The client records its fields at a mismatch; when the snapshot comes, its item list is the
 * server's copy (as the game saves and loads an item on this client, so fields the save leaves out or the load resets
 * show up on both lines), and the record and the state after applying are each compared with it. The snapshot's own
 * item digests tell which items really still differ after applying. Main thread only.
 */
internal class FieldDiff {
  /** One item's per-group hashes, its values by name, and its whole digest. */
  class Fields(val type: String, val hashes: Map<String, Long>, val values: Map<String, String>, val digest: Long) {
    companion object {
      fun of(item: InventoryItem): Fields {
        val f = ItemDigests.fields(item)
        return Fields(item.fullType, f.hashes, f.values, ItemDigests.item(item))
      }
    }
  }

  private val atMismatch = HashMap<WireKey, Map<Int, Fields>>()

  fun mismatch(key: WireKey, name: String, items: Collection<InventoryItem>, mine: Long, server: Long) {
    atMismatch[key] = of(items)
    ServerReborn.log {
      "field diff: $name differs (client ${"%016x".format(mine)}, server ${"%016x".format(server)}); " +
        "${items.size} items recorded, compared when the snapshot comes"
    }
  }

  /**
   * After a snapshot was applied: [before] is the client's state when it was applied, used when no mismatch was
   * recorded (an open check); [digests]: the server's item digests from the snapshot.
   */
  fun applied(
    key: WireKey,
    name: String,
    before: Map<Int, Fields>,
    after: Map<Int, Fields>,
    server: Map<Int, Fields>,
    digests: Map<Int, Long>,
  ) {
    val recorded = atMismatch.remove(key)
    val moment = if (recorded != null) "at the mismatch" else "before the snapshot"
    log(name, moment, recorded ?: before, server, emptyMap())
    log(name, "after the snapshot", after, server, digests)
  }

  companion object {
    /** Differences listed per group and item; the rest are counted. */
    private const val SHOWN = 12

    fun of(items: Collection<InventoryItem>): Map<Int, Fields> = items.associate { it.getID() to Fields.of(it) }

    // with digests: an item marked "equal" matches the server's own digest, so its listed fields differ only from the
    // saved copy
    private fun log(
      name: String,
      moment: String,
      client: Map<Int, Fields>,
      server: Map<Int, Fields>,
      digests: Map<Int, Long>,
    ) {
      val lines = mutableListOf<String>()
      server.forEach { (id, s) ->
        val c = client[id]
        if (c == null) {
          lines += "$id (${s.type}): only on the server"
          return@forEach
        }
        val fields = s.hashes.filter { (field, hash) -> hash != c.hashes[field] }
          .map { (field, _) -> field + values(field, c.values, s.values) } +
          c.hashes.keys.filter { it !in s.hashes }.map { "$it (only on the client)" }
        val verdict = when (digests[id]) {
          null -> ""
          c.digest -> " [equal]"
          else -> " [differs]"
        }
        if (fields.isNotEmpty() || verdict == " [differs]") {
          lines += "$id (${s.type}): ${fields.joinToString("; ")}$verdict"
        }
      }
      client.forEach { (id, c) ->
        if (id !in server) lines += "$id (${c.type}): only on the client"
      }
      ServerReborn.log {
        "field diff: $name $moment, against the server's copy: " +
          if (lines.isEmpty()) "no item differs" else lines.joinToString(" | ")
      }
    }

    // the group's values that differ, as "{name c=<client> s=<server>, ...}"; a value only one side has shows "-"
    private fun values(group: String, client: Map<String, String>, server: Map<String, String>): String {
      val prefix = "$group."
      val differing = (client.keys + server.keys).filter { it.startsWith(prefix) }.toSortedSet()
        .map { n -> Triple(n, client[n] ?: "-", server[n] ?: "-") }
        .filter { (_, c, s) -> c != s }
      val out = differing.take(SHOWN).map { (n, c, s) -> "${n.substring(prefix.length)} c=$c s=$s" } +
        listOfNotNull((differing.size - SHOWN).takeIf { it > 0 }?.let { "+$it more" })
      return if (out.isEmpty()) "" else out.joinToString(", ", "{", "}")
    }
  }
}
