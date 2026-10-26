package com.dont39.pz.serverreborn.networksyncoptimization.protocol

import com.dont39.pz.serverreborn.networksyncoptimization.protocol.Message.Bye
import com.dont39.pz.serverreborn.networksyncoptimization.protocol.Message.Hello
import com.dont39.pz.serverreborn.networksyncoptimization.protocol.Message.OpenCheck
import com.dont39.pz.serverreborn.networksyncoptimization.protocol.Message.Snapshot
import com.dont39.pz.serverreborn.networksyncoptimization.protocol.Message.SnapshotRequest
import com.dont39.pz.serverreborn.networksyncoptimization.protocol.Message.SyncFrame
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer

/**
 * The messages' bytes after the packet header: `[byte protocol version][byte kind][body]`, big-endian. Snapshots are
 * written with [SnapshotWriter], which streams the game's payloads into the buffer.
 */
internal object Codec {
  const val HELLO: Byte = 1
  const val SYNC_FRAME: Byte = 2
  const val SNAPSHOT_REQUEST: Byte = 3
  const val SNAPSHOT: Byte = 4
  const val OPEN_CHECK: Byte = 5
  const val BYE: Byte = 6

  // a frame entry's flags
  const val GONE = 1
  const val CHANGED = 2

  private const val MAX_STRING = 256

  fun encode(message: Message, out: ByteBuffer) {
    when (message) {
      is Hello -> {
        header(out, HELLO)
        val text = message.modVersion.toByteArray(Charsets.UTF_8)
        require(text.size <= MAX_STRING) { "a mod version of ${text.size} bytes" }
        out.putShort(text.size.toShort()).put(text)
      }
      is SyncFrame -> {
        header(out, SYNC_FRAME)
        out.putLong(message.version).putInt(message.entries.size)
        message.entries.forEach { e ->
          e.key.write(out)
          out.put(((if (e.gone) GONE else 0) or (if (e.changed) CHANGED else 0)).toByte())
          if (!e.gone) {
            out.putLong(e.digest)
          }
        }
      }
      is SnapshotRequest -> {
        header(out, SNAPSHOT_REQUEST)
        out.putInt(message.keys.size)
        message.keys.forEach { it.write(out) }
      }
      is OpenCheck -> {
        header(out, OPEN_CHECK)
        message.key.write(out)
        out.putLong(message.digest)
      }
      Bye -> header(out, BYE)
      is Snapshot -> throw IllegalArgumentException("snapshots are written with SnapshotWriter")
    }
  }

  /** Bytes [entry] takes in a frame, to split frames by size. */
  fun size(entry: SyncFrame.Entry): Int = entry.key.size + 1 + if (entry.gone) 0 else Long.SIZE_BYTES

  internal fun header(out: ByteBuffer, kind: Byte) {
    out.put(Protocol.VERSION.toByte()).put(kind)
  }

  /** Reads all of [input]; throws [ProtocolException] for anything that is not one whole message. */
  fun decode(input: ByteBuffer): Message {
    try {
      val version = input.get().toInt() and 0xFF
      if (version != Protocol.VERSION) {
        throw ProtocolException("protocol version $version, this version of the mod speaks ${Protocol.VERSION}")
      }
      val message = when (val kind = input.get()) {
        HELLO -> Hello(string(input))
        SYNC_FRAME -> frame(input)
        SNAPSHOT_REQUEST -> SnapshotRequest(List(count(input, Int.SIZE_BYTES)) { WireKey.read(input) })
        SNAPSHOT -> snapshot(input)
        OPEN_CHECK -> OpenCheck(WireKey.read(input), input.getLong())
        BYE -> Bye
        else -> throw ProtocolException("unknown message kind $kind")
      }
      if (input.hasRemaining()) {
        throw ProtocolException("${input.remaining()} bytes after a whole ${message.javaClass.simpleName}")
      }
      return message
    } catch (e: BufferUnderflowException) {
      throw ProtocolException("a message ends early", e)
    }
  }

  private fun string(input: ByteBuffer): String {
    val length = input.getShort().toInt() and 0xFFFF
    if (length > MAX_STRING) {
      throw ProtocolException("a string of $length bytes")
    }
    return ByteArray(length).also { input.get(it) }.toString(Charsets.UTF_8)
  }

  private fun frame(input: ByteBuffer): SyncFrame {
    val version = input.getLong()
    val entries = List(count(input, Int.SIZE_BYTES)) {
      val key = WireKey.read(input)
      val flags = input.get().toInt()
      if (flags and (GONE or CHANGED).inv() != 0) {
        throw ProtocolException("unknown frame entry flags $flags")
      }
      val gone = flags and GONE != 0
      SyncFrame.Entry(key, gone, if (gone) 0 else input.getLong(), flags and CHANGED != 0)
    }
    return SyncFrame(version, entries)
  }

  private fun snapshot(input: ByteBuffer): Snapshot {
    val key = WireKey.read(input)
    val version = input.getLong()
    val state = input.get().toInt()
    val states = Snapshot.State.entries
    if (state !in states.indices) {
      throw ProtocolException("unknown snapshot state $state")
    }
    val digest = input.getLong()
    val add = blob(input)
    val items = List(count(input, Int.SIZE_BYTES * 5 + Long.SIZE_BYTES)) {
      Snapshot.Item(input.getInt(), input.getLong(), blob(input), blob(input), blob(input), blob(input))
    }
    return Snapshot(key, version, states[state], digest, add, items)
  }

  /** A count whose elements take at least [minSize] bytes each, so a bad one cannot allocate much. */
  private fun count(input: ByteBuffer, minSize: Int): Int {
    val count = input.getInt()
    if (count < 0 || count.toLong() * minSize > input.remaining()) {
      throw ProtocolException("a count of $count with ${input.remaining()} bytes left")
    }
    return count
  }

  private fun blob(input: ByteBuffer): ByteArray {
    val length = input.getInt()
    if (length < 0 || length > input.remaining()) {
      throw ProtocolException("a payload of $length bytes with ${input.remaining()} left")
    }
    return ByteArray(length).also { input.get(it) }
  }
}
