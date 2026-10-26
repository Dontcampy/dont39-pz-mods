package com.dont39.pz.serverreborn.networksyncoptimization.protocol

/**
 * A 64-bit hash over a sequence of values, the same on every JVM: both sides compare it. Not cryptographic; it only
 * has to tell states apart that differ by accident.
 */
internal class Digest {
  private var h = SEED
  private var count = 0

  fun putLong(v: Long): Digest {
    h = (h xor mix(v + GOLDEN)).rotateLeft(29) * STEP + GOLDEN
    count++
    return this
  }

  fun putInt(v: Int): Digest = putLong(v.toLong())

  fun putBoolean(v: Boolean): Digest = putLong(if (v) 1 else 0)

  /** By bit pattern: both sides read the same float, so no tolerance; NaNs are one value. */
  fun putFloat(v: Float): Digest = putInt(v.toBits())

  /** Null and "" differ. */
  fun putString(s: String?): Digest = if (s == null) putLong(-1) else putBytes(s.toByteArray(Charsets.UTF_8))

  fun putBytes(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size): Digest {
    putLong(length.toLong())
    val end = offset + length
    var i = offset
    while (i + Long.SIZE_BYTES <= end) {
      var v = 0L
      for (j in 0 until Long.SIZE_BYTES) {
        v = (v shl 8) or (bytes[i + j].toLong() and 0xFF)
      }
      putLong(v)
      i += Long.SIZE_BYTES
    }
    var tail = 0L
    while (i < end) {
      tail = (tail shl 8) or (bytes[i].toLong() and 0xFF)
      i++
    }
    return putLong(tail)
  }

  fun get(): Long = mix(h xor mix(count.toLong()))

  companion object {
    private const val SEED = 0x5347_6469_6765_7374L
    private val GOLDEN = 0x9E37_79B9_7F4A_7C15uL.toLong()
    private val STEP = 0xBF58_476D_1CE4_E5B9uL.toLong()
    private val MIX1 = 0xBF58_476D_1CE4_E5B9uL.toLong()
    private val MIX2 = 0x94D0_49BB_1331_11EBuL.toLong()

    /** SplitMix64's finalizer: every input bit reaches every output bit. */
    fun mix(value: Long): Long {
      var z = (value xor (value ushr 30)) * MIX1
      z = (z xor (z ushr 27)) * MIX2
      return z xor (z ushr 31)
    }

    /** One value's hash, for [Unordered.add]. */
    fun of(vararg values: Long): Long {
      val d = Digest()
      values.forEach { d.putLong(it) }
      return d.get()
    }
  }
}

/**
 * A hash of a multiset: the same whatever order the elements come in, as a container's items and a Lua table's pairs
 * differ in order between the two sides.
 */
internal class Unordered {
  private var sum = 0L
  private var sumOfSquares = 0L

  var count = 0
    private set

  fun add(elementHash: Long): Unordered {
    val m = Digest.mix(elementHash)
    sum += m
    sumOfSquares += m * m
    count++
    return this
  }

  fun get(): Long = Digest().putLong(count.toLong()).putLong(sum).putLong(sumOfSquares).get()
}

/**
 * A container's digest: over each item's id, full type and item digest, in any order, as the two sides list a
 * container's items in different orders.
 */
internal class ContainerDigest {
  private val items = Unordered()

  fun add(id: Int, fullType: String, itemDigest: Long): ContainerDigest {
    items.add(Digest().putInt(id).putString(fullType).putLong(itemDigest).get())
    return this
  }

  fun get(): Long = items.get()
}
