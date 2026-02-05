package org.abstractica.clientserver.impl.protocol;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Encodes and decodes wire protocol packets to/from ByteBuffer.
 *
 * <p>All methods assume big-endian byte order (network order).</p>
 */
public final class PacketCodec
{
    private PacketCodec() {}

    // ========== Encoding ==========

    /**
     * Encodes a ClientHello packet.
     *
     * @param packet the packet to encode
     * @return encoded bytes
     */
    public static ByteBuffer encode(ClientHello packet)
    {
        ByteBuffer buffer = ByteBuffer.allocate(1 + 1 + 32);
        buffer.put((byte) PacketType.CLIENT_HELLO.getId());
        buffer.put((byte) packet.version());
        buffer.put(packet.clientPublicKey());
        return buffer.flip();
    }

    /**
     * Encodes a ServerHello packet.
     *
     * @param packet the packet to encode
     * @return encoded bytes
     */
    public static ByteBuffer encode(ServerHello packet)
    {
        ByteBuffer buffer = ByteBuffer.allocate(1 + 1 + 32 + 64);
        buffer.put((byte) PacketType.SERVER_HELLO.getId());
        buffer.put((byte) packet.version());
        buffer.put(packet.serverPublicKey());
        buffer.put(packet.signature());
        return buffer.flip();
    }

    /**
     * Encodes a Connect packet (for encrypted payload).
     *
     * @param packet the packet to encode
     * @return encoded bytes
     */
    public static ByteBuffer encode(Connect packet)
    {
        int size = 1 + 32 + 1; // type + hash + tokenLength
        if (packet.sessionToken().isPresent())
        {
            size += 16 + 4; // token + lastReceivedSeq
        }

        ByteBuffer buffer = ByteBuffer.allocate(size);
        buffer.put((byte) PacketType.CONNECT.getId());
        buffer.put(packet.protocolHash());

        if (packet.sessionToken().isPresent())
        {
            buffer.put((byte) 16);
            buffer.put(packet.sessionToken().get());
            buffer.putInt(packet.lastReceivedSeq().orElse(0));
        }
        else
        {
            buffer.put((byte) 0);
        }

        return buffer.flip();
    }

    /**
     * Encodes an Accept packet (for encrypted payload).
     *
     * @param packet the packet to encode
     * @return encoded bytes
     */
    public static ByteBuffer encode(Accept packet)
    {
        ByteBuffer buffer = ByteBuffer.allocate(1 + 16 + 4 + 4 + 4);
        buffer.put((byte) PacketType.ACCEPT.getId());
        buffer.put(packet.sessionToken());
        buffer.putInt(packet.heartbeatIntervalMs());
        buffer.putInt(packet.sessionTimeoutMs());
        buffer.putInt(packet.lastReceivedSeq());
        return buffer.flip();
    }

    /**
     * Encodes a Reject packet (for encrypted payload).
     *
     * @param packet the packet to encode
     * @return encoded bytes
     */
    public static ByteBuffer encode(Reject packet)
    {
        byte[] messageBytes = packet.message().getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(1 + 1 + 2 + messageBytes.length);
        buffer.put((byte) PacketType.REJECT.getId());
        buffer.put((byte) packet.reasonCode().getCode());
        buffer.putShort((short) messageBytes.length);
        buffer.put(messageBytes);
        return buffer.flip();
    }

    /**
     * Encodes a Data packet (for encrypted payload).
     *
     * @param packet the packet to encode
     * @return encoded bytes
     */
    public static ByteBuffer encode(Data packet)
    {
        int size = 1 + 1 + 2 + packet.payload().length; // type + flags + msgTypeId + payload
        if (packet.reliable())
        {
            size += 4; // sequence
        }
        if (packet.ackSequence().isPresent())
        {
            size += 4; // ackSequence
        }

        ByteBuffer buffer = ByteBuffer.allocate(size);
        buffer.put((byte) PacketType.DATA.getId());
        buffer.put((byte) packet.getFlags());

        if (packet.reliable())
        {
            buffer.putInt(packet.sequence().orElseThrow());
        }
        if (packet.ackSequence().isPresent())
        {
            buffer.putInt(packet.ackSequence().get());
        }

        buffer.putShort((short) packet.messageTypeId());
        buffer.put(packet.payload());

        return buffer.flip();
    }

    /**
     * Encodes an Ack packet (for encrypted payload).
     *
     * @param packet the packet to encode
     * @return encoded bytes
     */
    public static ByteBuffer encode(Ack packet)
    {
        ByteBuffer buffer = ByteBuffer.allocate(1 + 4 + 4);
        buffer.put((byte) PacketType.ACK.getId());
        buffer.putInt(packet.ackSequence());
        buffer.putInt(packet.ackBitmap());
        return buffer.flip();
    }

    /**
     * Encodes a Heartbeat packet (for encrypted payload).
     *
     * @param packet the packet to encode
     * @return encoded bytes
     */
    public static ByteBuffer encode(Heartbeat packet)
    {
        ByteBuffer buffer = ByteBuffer.allocate(1 + 8);
        buffer.put((byte) PacketType.HEARTBEAT.getId());
        buffer.putLong(packet.timestamp());
        return buffer.flip();
    }

    /**
     * Encodes a HeartbeatAck packet (for encrypted payload).
     *
     * @param packet the packet to encode
     * @return encoded bytes
     */
    public static ByteBuffer encode(HeartbeatAck packet)
    {
        ByteBuffer buffer = ByteBuffer.allocate(1 + 8 + 8);
        buffer.put((byte) PacketType.HEARTBEAT_ACK.getId());
        buffer.putLong(packet.echoTimestamp());
        buffer.putLong(packet.timestamp());
        return buffer.flip();
    }

    /**
     * Encodes a Disconnect packet (for encrypted payload).
     *
     * @param packet the packet to encode
     * @return encoded bytes
     */
    public static ByteBuffer encode(Disconnect packet)
    {
        byte[] messageBytes = packet.message().getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(1 + 1 + 2 + messageBytes.length);
        buffer.put((byte) PacketType.DISCONNECT.getId());
        buffer.put((byte) packet.reasonCode().getCode());
        buffer.putShort((short) messageBytes.length);
        buffer.put(messageBytes);
        return buffer.flip();
    }

    // ========== Decoding ==========

    /**
     * Reads the packet type from a buffer without consuming it.
     *
     * @param buffer the buffer to peek
     * @return the packet type
     */
    public static PacketType peekType(ByteBuffer buffer)
    {
        return PacketType.fromId(buffer.get(buffer.position()) & 0xFF);
    }

    /**
     * Decodes a ClientHello packet.
     *
     * @param buffer the buffer to read from
     * @return decoded packet
     */
    public static ClientHello decodeClientHello(ByteBuffer buffer)
    {
        int type = buffer.get() & 0xFF;
        if (type != PacketType.CLIENT_HELLO.getId())
        {
            throw new IllegalArgumentException("Expected CLIENT_HELLO, got: 0x" + Integer.toHexString(type));
        }

        int version = buffer.get() & 0xFF;
        byte[] publicKey = new byte[32];
        buffer.get(publicKey);

        return new ClientHello(version, publicKey);
    }

    /**
     * Decodes a ServerHello packet.
     *
     * @param buffer the buffer to read from
     * @return decoded packet
     */
    public static ServerHello decodeServerHello(ByteBuffer buffer)
    {
        int type = buffer.get() & 0xFF;
        if (type != PacketType.SERVER_HELLO.getId())
        {
            throw new IllegalArgumentException("Expected SERVER_HELLO, got: 0x" + Integer.toHexString(type));
        }

        int version = buffer.get() & 0xFF;
        byte[] publicKey = new byte[32];
        buffer.get(publicKey);
        byte[] signature = new byte[64];
        buffer.get(signature);

        return new ServerHello(version, publicKey, signature);
    }

    /**
     * Decodes a Connect packet.
     *
     * @param buffer the buffer to read from
     * @return decoded packet
     */
    public static Connect decodeConnect(ByteBuffer buffer)
    {
        int type = buffer.get() & 0xFF;
        if (type != PacketType.CONNECT.getId())
        {
            throw new IllegalArgumentException("Expected CONNECT, got: 0x" + Integer.toHexString(type));
        }

        byte[] protocolHash = new byte[32];
        buffer.get(protocolHash);

        int tokenLength = buffer.get() & 0xFF;

        if (tokenLength == 0)
        {
            return Connect.newConnection(protocolHash);
        }
        else
        {
            byte[] sessionToken = new byte[tokenLength];
            buffer.get(sessionToken);
            int lastReceivedSeq = buffer.getInt();
            return Connect.reconnect(protocolHash, sessionToken, lastReceivedSeq);
        }
    }

    /**
     * Decodes an Accept packet.
     *
     * @param buffer the buffer to read from
     * @return decoded packet
     */
    public static Accept decodeAccept(ByteBuffer buffer)
    {
        int type = buffer.get() & 0xFF;
        if (type != PacketType.ACCEPT.getId())
        {
            throw new IllegalArgumentException("Expected ACCEPT, got: 0x" + Integer.toHexString(type));
        }

        byte[] sessionToken = new byte[16];
        buffer.get(sessionToken);
        int heartbeatIntervalMs = buffer.getInt();
        int sessionTimeoutMs = buffer.getInt();
        int lastReceivedSeq = buffer.getInt();

        return new Accept(sessionToken, heartbeatIntervalMs, sessionTimeoutMs, lastReceivedSeq);
    }

    /**
     * Decodes a Reject packet.
     *
     * @param buffer the buffer to read from
     * @return decoded packet
     */
    public static Reject decodeReject(ByteBuffer buffer)
    {
        int type = buffer.get() & 0xFF;
        if (type != PacketType.REJECT.getId())
        {
            throw new IllegalArgumentException("Expected REJECT, got: 0x" + Integer.toHexString(type));
        }

        int reasonCode = buffer.get() & 0xFF;
        int messageLength = buffer.getShort() & 0xFFFF;
        byte[] messageBytes = new byte[messageLength];
        buffer.get(messageBytes);
        String message = new String(messageBytes, StandardCharsets.UTF_8);

        return new Reject(Reject.RejectReason.fromCode(reasonCode), message);
    }

    /**
     * Decodes a Data packet.
     *
     * @param buffer the buffer to read from
     * @return decoded packet
     */
    public static Data decodeData(ByteBuffer buffer)
    {
        int type = buffer.get() & 0xFF;
        if (type != PacketType.DATA.getId())
        {
            throw new IllegalArgumentException("Expected DATA, got: 0x" + Integer.toHexString(type));
        }

        int flags = buffer.get() & 0xFF;
        boolean reliable = (flags & Data.FLAG_RELIABLE) != 0;
        boolean hasAck = (flags & Data.FLAG_HAS_ACK) != 0;

        Optional<Integer> sequence = Optional.empty();
        if (reliable)
        {
            sequence = Optional.of(buffer.getInt());
        }

        Optional<Integer> ackSequence = Optional.empty();
        if (hasAck)
        {
            ackSequence = Optional.of(buffer.getInt());
        }

        int messageTypeId = buffer.getShort() & 0xFFFF;
        byte[] payload = new byte[buffer.remaining()];
        buffer.get(payload);

        return new Data(reliable, sequence, ackSequence, messageTypeId, payload);
    }

    /**
     * Decodes an Ack packet.
     *
     * @param buffer the buffer to read from
     * @return decoded packet
     */
    public static Ack decodeAck(ByteBuffer buffer)
    {
        int type = buffer.get() & 0xFF;
        if (type != PacketType.ACK.getId())
        {
            throw new IllegalArgumentException("Expected ACK, got: 0x" + Integer.toHexString(type));
        }

        int ackSequence = buffer.getInt();
        int ackBitmap = buffer.getInt();

        return new Ack(ackSequence, ackBitmap);
    }

    /**
     * Decodes a Heartbeat packet.
     *
     * @param buffer the buffer to read from
     * @return decoded packet
     */
    public static Heartbeat decodeHeartbeat(ByteBuffer buffer)
    {
        int type = buffer.get() & 0xFF;
        if (type != PacketType.HEARTBEAT.getId())
        {
            throw new IllegalArgumentException("Expected HEARTBEAT, got: 0x" + Integer.toHexString(type));
        }

        long timestamp = buffer.getLong();
        return new Heartbeat(timestamp);
    }

    /**
     * Decodes a HeartbeatAck packet.
     *
     * @param buffer the buffer to read from
     * @return decoded packet
     */
    public static HeartbeatAck decodeHeartbeatAck(ByteBuffer buffer)
    {
        int type = buffer.get() & 0xFF;
        if (type != PacketType.HEARTBEAT_ACK.getId())
        {
            throw new IllegalArgumentException("Expected HEARTBEAT_ACK, got: 0x" + Integer.toHexString(type));
        }

        long echoTimestamp = buffer.getLong();
        long timestamp = buffer.getLong();

        return new HeartbeatAck(echoTimestamp, timestamp);
    }

    /**
     * Decodes a Disconnect packet.
     *
     * @param buffer the buffer to read from
     * @return decoded packet
     */
    public static Disconnect decodeDisconnect(ByteBuffer buffer)
    {
        int type = buffer.get() & 0xFF;
        if (type != PacketType.DISCONNECT.getId())
        {
            throw new IllegalArgumentException("Expected DISCONNECT, got: 0x" + Integer.toHexString(type));
        }

        int reasonCode = buffer.get() & 0xFF;
        int messageLength = buffer.getShort() & 0xFFFF;
        byte[] messageBytes = new byte[messageLength];
        buffer.get(messageBytes);
        String message = new String(messageBytes, StandardCharsets.UTF_8);

        return new Disconnect(Disconnect.DisconnectCode.fromCode(reasonCode), message);
    }

    /**
     * Decodes any packet based on its type byte.
     *
     * @param buffer the buffer to read from
     * @return decoded packet (one of the packet record types)
     */
    public static Object decode(ByteBuffer buffer)
    {
        PacketType type = peekType(buffer);
        return switch (type)
        {
            case CLIENT_HELLO -> decodeClientHello(buffer);
            case SERVER_HELLO -> decodeServerHello(buffer);
            case CONNECT -> decodeConnect(buffer);
            case ACCEPT -> decodeAccept(buffer);
            case REJECT -> decodeReject(buffer);
            case DATA -> decodeData(buffer);
            case ACK -> decodeAck(buffer);
            case HEARTBEAT -> decodeHeartbeat(buffer);
            case HEARTBEAT_ACK -> decodeHeartbeatAck(buffer);
            case DISCONNECT -> decodeDisconnect(buffer);
            case ENCRYPTED_ENVELOPE -> throw new IllegalArgumentException(
                    "ENCRYPTED_ENVELOPE is an outer wrapper, not a decodable packet type");
        };
    }
}
