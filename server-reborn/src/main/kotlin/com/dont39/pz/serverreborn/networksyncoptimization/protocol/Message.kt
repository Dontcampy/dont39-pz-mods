package com.dont39.pz.serverreborn.networksyncoptimization.protocol

/** The mod's messages; [Codec] reads and writes them. */
internal sealed interface Message {
  /** Client to server, once per join after PlayerConnect: the client checks and wants frames. */
  data class Hello(val modVersion: String) : Message

  /**
   * Server to client, at the end of a world tick: the touched objects' state as the server has it, with the frame's
   * number as every entry's version.
   */
  data class SyncFrame(val version: Long, val entries: List<Entry>) : Message {
    /**
     * [gone]: the server no longer finds the object there; [digest] is then 0. [changed]: its contents changed on the
     * server this tick, by whatever route reached the clients (D7); the client compares it only after a grace period,
     * as a mod's own sync of the change may arrive after the frame.
     */
    data class Entry(val key: WireKey, val gone: Boolean, val digest: Long, val changed: Boolean = false) {
      fun asChanged() = if (changed) this else copy(changed = true)
    }
  }

  /** Client to server: the objects whose digest differed. */
  data class SnapshotRequest(val keys: List<WireKey>) : Message

  /**
   * Server to client: an object's full state. [add] is the game's AddInventoryItemToContainer payload of the items,
   * and each item carries the game's field packets' payloads; an empty array stands for one not sent. Written with
   * [SnapshotWriter].
   */
  class Snapshot(
    val key: WireKey,
    val version: Long,
    val state: State,
    val digest: Long,
    val add: ByteArray,
    val items: List<Item>,
  ) : Message {
    enum class State {
      PRESENT,

      /** The server no longer finds the object. */
      GONE,

      /** The object does not fit into one packet; it is not sent. */
      TOO_LARGE,
    }

    /** [digest]: the server's item digest, to tell which items a snapshot could not make equal. */
    class Item(
      val id: Int,
      val digest: Long,
      val fields: ByteArray,
      val modData: ByteArray,
      val stats: ByteArray,
      val weapon: ByteArray,
    )
  }

  /** Client to server, when the player opens a container: the client's digest of it. */
  data class OpenCheck(val key: WireKey, val digest: Long) : Message

  /** Client to server: the client stopped checking for this session (it failed, or the player left). */
  data object Bye : Message
}
