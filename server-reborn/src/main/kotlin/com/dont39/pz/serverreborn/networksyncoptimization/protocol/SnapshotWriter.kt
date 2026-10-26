package com.dont39.pz.serverreborn.networksyncoptimization.protocol

import com.dont39.pz.serverreborn.networksyncoptimization.protocol.Message.Snapshot
import java.nio.ByteBuffer

/** Writes one payload at the buffer's position. */
internal typealias Payload = (ByteBuffer) -> Unit

/**
 * Writes a [Snapshot] straight into a buffer, so the game's packet classes write their payloads in place: [start],
 * [add] once, [item] per item, [finish].
 */
internal class SnapshotWriter private constructor(private val out: ByteBuffer) {
  private var countAt = -1
  private var count = 0

  /** The items' AddInventoryItemToContainer payload; null for none. */
  fun add(add: Payload?): SnapshotWriter {
    check(countAt < 0) { "add was written already" }
    blob(add)
    countAt = out.position()
    out.putInt(0)
    return this
  }

  /** [digest]: the item's digest on the server. Null for a payload not sent. */
  fun item(
    id: Int,
    digest: Long,
    fields: Payload?,
    modData: Payload?,
    stats: Payload?,
    weapon: Payload?,
  ): SnapshotWriter {
    check(countAt >= 0) { "items come after add" }
    out.putInt(id).putLong(digest)
    listOf(fields, modData, stats, weapon).forEach(::blob)
    count++
    return this
  }

  fun finish() {
    check(countAt >= 0) { "a snapshot without add" }
    out.putInt(countAt, count)
  }

  private fun blob(payload: Payload?) {
    val at = out.position()
    out.putInt(0)
    if (payload != null) {
      payload(out)
      out.putInt(at, out.position() - at - Int.SIZE_BYTES)
    }
  }

  companion object {
    fun start(out: ByteBuffer, key: WireKey, version: Long, state: Snapshot.State, digest: Long): SnapshotWriter {
      Codec.header(out, Codec.SNAPSHOT)
      key.write(out)
      out.putLong(version).put(state.ordinal.toByte()).putLong(digest)
      return SnapshotWriter(out)
    }
  }
}
