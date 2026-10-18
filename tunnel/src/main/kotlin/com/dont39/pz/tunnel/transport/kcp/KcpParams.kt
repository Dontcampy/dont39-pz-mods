package com.dont39.pz.tunnel.transport.kcp

import com.dont39.pz.tunnel.TunnelOptions
import java.nio.ByteBuffer

/**
 * The KCP and FEC settings of a tunnel. The server reads them from its options and sends them in the WELCOME, so both
 * ends of a tunnel run the same ones. Ranges match `sandbox-options.txt`.
 */
internal data class KcpParams(
  val intervalMillis: Int,
  val resend: Int,
  val noCongestion: Boolean,
  val window: Int,
  val mtu: Int,
  val dataShards: Int,
  val parityShards: Int,
  val flushMillis: Int,
) {
  /** Null when every value is in its range, else which is not. */
  fun problem(): String? = listOf(
    Triple("KcpInterval", intervalMillis, INTERVAL),
    Triple("KcpResend", resend, RESEND),
    Triple("KcpWindow", window, WINDOW),
    Triple("KcpMtu", mtu, MTU),
    Triple("FecDataShards", dataShards, DATA_SHARDS),
    Triple("FecParityShards", parityShards, PARITY_SHARDS),
    Triple("FecFlushMillis", flushMillis, FLUSH),
  ).firstOrNull { (_, value, range) -> value !in range }
    ?.let { (name, value, range) -> "$name is $value, outside ${range.first}..${range.last}" }

  fun write(out: ByteBuffer) {
    out.putShort(intervalMillis.toShort())
    out.put(resend.toByte())
    out.put(if (noCongestion) 1 else 0)
    out.putShort(window.toShort())
    out.putShort(mtu.toShort())
    out.put(dataShards.toByte())
    out.put(parityShards.toByte())
    out.putShort(flushMillis.toShort())
  }

  override fun toString(): String =
    "interval $intervalMillis ms, fast resend ${if (resend == 0) "off" else "after $resend"}, congestion window " +
      "${if (noCongestion) "off" else "on"}, window $window, MTU $mtu, FEC " +
      if (parityShards == 0) "off" else "$dataShards+$parityShards, flush $flushMillis ms"

  companion object {
    const val OPTION_PREFIX = "Dont39AllInOne_Tunnel."

    /** Bytes [write] puts. */
    const val SIZE = 12

    private val INTERVAL = 10..100
    private val RESEND = 0..10
    private val WINDOW = 32..8192
    private val MTU = 576..1450
    private val DATA_SHARDS = 1..64
    private val PARITY_SHARDS = 0..32
    private val FLUSH = 1..1000

    /** Throws when an option is missing or out of its range. */
    fun read(options: TunnelOptions): KcpParams {
      fun int(name: String) = options.int(OPTION_PREFIX + name)
      val params = KcpParams(
        intervalMillis = int("KcpInterval"),
        resend = int("KcpResend"),
        noCongestion = options.boolean(OPTION_PREFIX + "KcpNoCongestion"),
        window = int("KcpWindow"),
        mtu = int("KcpMtu"),
        dataShards = int("FecDataShards"),
        parityShards = int("FecParityShards"),
        flushMillis = int("FecFlushMillis"),
      )
      // the game keeps option values in the ranges of sandbox-options.txt, so this is a mismatch
      params.problem()?.let { throw IllegalStateException("the sandbox option $OPTION_PREFIX$it") }
      return params
    }

    /** Null when [input] has too few bytes; the caller checks [problem]. */
    fun read(input: ByteBuffer): KcpParams? {
      if (input.remaining() < SIZE) return null
      return KcpParams(
        intervalMillis = input.short.toInt() and 0xffff,
        resend = input.get().toInt() and 0xff,
        noCongestion = input.get().toInt() != 0,
        window = input.short.toInt() and 0xffff,
        mtu = input.short.toInt() and 0xffff,
        dataShards = input.get().toInt() and 0xff,
        parityShards = input.get().toInt() and 0xff,
        flushMillis = input.short.toInt() and 0xffff,
      )
    }
  }
}
