package com.dont39.pz.serverreborn.network.protocol

/** Wire constants both sides share. JDK types only, like the rest of this package: its tests run without the game. */
internal object Protocol {
  /**
   * The version a server advertises and every message starts with. Bump it with any change to a message, a key's wire
   * form or the fields a digest covers: both sides must hash the same fields.
   */
  const val VERSION = 2

  /** The game's first byte of a packet with a short id, `RakNetPeerInterface.ID_USER_PACKET_ENUM`. */
  const val USER_PACKET = 134

  /** "SG": far above the game's packet ids, which are the ordinals of its 298 packet types. */
  const val PACKET_ID: Short = 0x5347

  // RakNet's values, as PacketTypes and UdpConnection.endPacket use them
  const val PRIORITY_HIGH = 1
  const val RELIABLE_ORDERED = 3

  /** The channel the game's container add/remove already use; the item field packets join it (R2). */
  const val SHARED_CHANNEL: Byte = 1

  /** PlayerConnect's channel: Hello must arrive after it. */
  const val CONNECT_CHANNEL: Byte = 0
}

/** A message that does not decode: it concerns the peer that sent it, not the mod. */
internal class ProtocolException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
