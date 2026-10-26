package com.dont39.pz.serverreborn.network.game

import com.dont39.pz.serverreborn.network.protocol.Protocol
import zombie.core.network.ByteBufferReader
import zombie.core.raknet.UdpConnection
import java.nio.ByteBuffer
import java.util.concurrent.locks.ReentrantLock

/** The mod's raw packets: header 134 plus [Protocol.PACKET_ID], then a `Codec` message. */
internal object Net {
  /**
   * Sends one packet on [connection], RELIABLE_ORDERED on [channel]; returns its size in bytes. Only from the mod's own
   * message handlers and the end-of-tick hook: inside a game send of the same thread the connection's lock is held
   * already and is reentrant, so this would overwrite the game's half-written packet. Throws instead of doing that.
   */
  fun send(connection: UdpConnection, channel: Byte, message: (ByteBuffer) -> Unit): Int {
    val lock = connection.bufferLock
    check(lock is ReentrantLock) {
      "the game's connection lock is a ${lock.javaClass.name}, not the ReentrantLock this version of the mod knows"
    }
    check(!lock.isHeldByCurrentThread) { "a send of the mod inside one of the game's on the same connection" }
    val out = connection.startPacket()
    try {
      out.putByte(Protocol.USER_PACKET)
      out.putShort(Protocol.PACKET_ID)
      message(out.bb)
      val size = out.position()
      connection.endPacket(Protocol.PRIORITY_HIGH, Protocol.RELIABLE_ORDERED, channel)
      return size
    } catch (t: Throwable) {
      connection.cancelPacket()
      throw t
    }
  }

  /** Whether [send] may run now: not inside one of the game's sends on [connection] on this thread. */
  fun canSend(connection: UdpConnection): Boolean =
    (connection.bufferLock as? ReentrantLock)?.isHeldByCurrentThread != true

  /** The payload after the 3-byte header the game already read; leaves [input] as it is. */
  fun payload(input: ByteBufferReader): ByteArray {
    val b = input.bb.duplicate()
    return ByteArray(b.remaining()).also { b.get(it) }
  }
}
