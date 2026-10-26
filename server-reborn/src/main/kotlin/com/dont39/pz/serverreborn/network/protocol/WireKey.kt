package com.dont39.pz.serverreborn.network.protocol

import java.nio.ByteBuffer

/**
 * A key as sent: the container as the game's `ContainerID.write` writes it, so the receiver resolves it with the
 * game's own `parse`, and the item id for an item.
 */
internal class WireKey private constructor(
  /** The game's bytes; not copied, do not change them. */
  val containerBytes: ByteArray,
  val isItem: Boolean,
  val itemId: Int,
) {
  init {
    require(containerBytes.size in 1..MAX_CONTAINER_BYTES) { "a container id of ${containerBytes.size} bytes" }
  }

  internal val size: Int get() = Short.SIZE_BYTES + containerBytes.size + 1 + if (isItem) Int.SIZE_BYTES else 0

  internal fun write(out: ByteBuffer) {
    out.putShort(containerBytes.size.toShort()).put(containerBytes).put((if (isItem) 1 else 0).toByte())
    if (isItem) {
      out.putInt(itemId)
    }
  }

  override fun equals(other: Any?) = other is WireKey && isItem == other.isItem && itemId == other.itemId &&
    containerBytes.contentEquals(other.containerBytes)

  override fun hashCode() = 31 * (31 * containerBytes.contentHashCode() + isItem.hashCode()) + itemId

  override fun toString() = (if (isItem) "item $itemId in " else "") + "container " + containerBytes.contentToString()

  companion object {
    const val MAX_CONTAINER_BYTES = 0xFFFF

    fun container(container: ByteArray) = WireKey(container, false, 0)

    fun item(container: ByteArray, itemId: Int) = WireKey(container, true, itemId)

    internal fun read(input: ByteBuffer): WireKey {
      val length = input.getShort().toInt() and MAX_CONTAINER_BYTES
      if (length == 0 || length > input.remaining()) {
        throw ProtocolException("a key says its container has $length bytes, ${input.remaining()} remain")
      }
      val bytes = ByteArray(length).also { input.get(it) }
      return when (input.get().toInt()) {
        0 -> container(bytes)
        1 -> item(bytes, input.getInt())
        else -> throw ProtocolException("a key is neither a container nor an item")
      }
    }
  }
}
