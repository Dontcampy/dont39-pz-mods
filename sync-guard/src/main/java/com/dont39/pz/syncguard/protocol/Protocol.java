package com.dont39.pz.syncguard.protocol;

/** Wire constants both sides share. JDK types only, like the rest of this package: its tests run without the game. */
public final class Protocol {
    /**
     * The version a server advertises and every message starts with. Bump it with any change to a message, a key's
     * wire form or the fields a digest covers: both sides must hash the same fields.
     */
    public static final int VERSION = 1;

    /** The game's first byte of a packet with a short id, {@code RakNetPeerInterface.ID_USER_PACKET_ENUM}. */
    public static final int USER_PACKET = 134;
    /** "SG": far above the game's packet ids, which are the ordinals of its 298 packet types. */
    public static final short PACKET_ID = 0x5347;

    // RakNet's values, as PacketTypes and UdpConnection.endPacket use them
    public static final int PRIORITY_HIGH = 1;
    public static final int RELIABLE_ORDERED = 3;
    /** The channel the game's container add/remove already use; the item field packets join it (R2). */
    public static final byte SHARED_CHANNEL = 1;
    /** PlayerConnect's channel: Hello must arrive after it. */
    public static final byte CONNECT_CHANNEL = 0;

    private Protocol() {
    }
}
