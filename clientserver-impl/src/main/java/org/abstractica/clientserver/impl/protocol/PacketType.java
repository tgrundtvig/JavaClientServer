package org.abstractica.clientserver.impl.protocol;

/**
 * Packet type identifiers as defined in the wire protocol.
 */
public enum PacketType
{
    // Unencrypted handshake packets (0x01 - 0x0F)
    CLIENT_HELLO(0x01, false),
    SERVER_HELLO(0x02, false),

    // Encrypted session packets (0x10 - 0x1F)
    CONNECT(0x10, true),
    ACCEPT(0x11, true),
    REJECT(0x12, true),

    // Encrypted data packets (0x20 - 0x2F)
    DATA(0x20, true),
    ACK(0x21, true),

    // Encrypted control packets (0x30 - 0x3F)
    HEARTBEAT(0x30, true),
    HEARTBEAT_ACK(0x31, true),

    // Encrypted disconnect (0x40 - 0x4F)
    DISCONNECT(0x40, true);

    private final int id;
    private final boolean encrypted;

    PacketType(int id, boolean encrypted)
    {
        this.id = id;
        this.encrypted = encrypted;
    }

    /**
     * Returns the wire protocol identifier for this packet type.
     *
     * @return the packet type ID (1 byte)
     */
    public int getId()
    {
        return id;
    }

    /**
     * Returns whether this packet type is sent encrypted.
     *
     * @return true if encrypted, false for handshake packets
     */
    public boolean isEncrypted()
    {
        return encrypted;
    }

    /**
     * Looks up a packet type by its wire protocol ID.
     *
     * @param id the packet type ID
     * @return the packet type
     * @throws IllegalArgumentException if ID is unknown
     */
    public static PacketType fromId(int id)
    {
        for (PacketType type : values())
        {
            if (type.id == id)
            {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown packet type ID: 0x" + Integer.toHexString(id));
    }
}
