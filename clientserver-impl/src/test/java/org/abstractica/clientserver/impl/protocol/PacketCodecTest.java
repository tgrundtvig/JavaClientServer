package org.abstractica.clientserver.impl.protocol;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link PacketCodec} round-trip encoding and decoding.
 */
class PacketCodecTest
{
    // ========== ClientHello ==========

    @Test
    void clientHello_roundTrip()
    {
        byte[] publicKey = new byte[32];
        Arrays.fill(publicKey, (byte) 0xAB);

        ClientHello original = new ClientHello(1, publicKey);
        ByteBuffer encoded = PacketCodec.encode(original);
        ClientHello decoded = PacketCodec.decodeClientHello(encoded);

        assertEquals(original.version(), decoded.version());
        assertArrayEquals(original.clientPublicKey(), decoded.clientPublicKey());
    }

    @Test
    void clientHello_wireFormat()
    {
        byte[] publicKey = new byte[32];
        for (int i = 0; i < 32; i++)
        {
            publicKey[i] = (byte) i;
        }

        ClientHello packet = new ClientHello(1, publicKey);
        ByteBuffer encoded = PacketCodec.encode(packet);

        assertEquals(34, encoded.remaining()); // 1 + 1 + 32
        assertEquals(0x01, encoded.get()); // type
        assertEquals(0x01, encoded.get()); // version
        for (int i = 0; i < 32; i++)
        {
            assertEquals((byte) i, encoded.get()); // public key
        }
    }

    // ========== ServerHello ==========

    @Test
    void serverHello_roundTrip()
    {
        byte[] publicKey = new byte[32];
        byte[] signature = new byte[64];
        Arrays.fill(publicKey, (byte) 0xCD);
        Arrays.fill(signature, (byte) 0xEF);

        ServerHello original = new ServerHello(1, publicKey, signature);
        ByteBuffer encoded = PacketCodec.encode(original);
        ServerHello decoded = PacketCodec.decodeServerHello(encoded);

        assertEquals(original.version(), decoded.version());
        assertArrayEquals(original.serverPublicKey(), decoded.serverPublicKey());
        assertArrayEquals(original.signature(), decoded.signature());
    }

    @Test
    void serverHello_wireFormat()
    {
        byte[] publicKey = new byte[32];
        byte[] signature = new byte[64];

        ServerHello packet = new ServerHello(1, publicKey, signature);
        ByteBuffer encoded = PacketCodec.encode(packet);

        assertEquals(98, encoded.remaining()); // 1 + 1 + 32 + 64
    }

    // ========== Connect ==========

    @Test
    void connect_newConnection_roundTrip()
    {
        byte[] protocolHash = new byte[32];
        Arrays.fill(protocolHash, (byte) 0x11);

        Connect original = Connect.newConnection(protocolHash);
        ByteBuffer encoded = PacketCodec.encode(original);
        Connect decoded = PacketCodec.decodeConnect(encoded);

        assertArrayEquals(original.protocolHash(), decoded.protocolHash());
        assertTrue(decoded.sessionToken().isEmpty());
        assertTrue(decoded.lastReceivedSeq().isEmpty());
    }

    @Test
    void connect_reconnect_roundTrip()
    {
        byte[] protocolHash = new byte[32];
        byte[] sessionToken = new byte[16];
        Arrays.fill(protocolHash, (byte) 0x22);
        Arrays.fill(sessionToken, (byte) 0x33);

        Connect original = Connect.reconnect(protocolHash, sessionToken, 42);
        ByteBuffer encoded = PacketCodec.encode(original);
        Connect decoded = PacketCodec.decodeConnect(encoded);

        assertArrayEquals(original.protocolHash(), decoded.protocolHash());
        assertTrue(decoded.sessionToken().isPresent());
        assertArrayEquals(sessionToken, decoded.sessionToken().get());
        assertEquals(42, decoded.lastReceivedSeq().orElse(-1));
    }

    @Test
    void connect_newConnection_wireFormat()
    {
        byte[] protocolHash = new byte[32];
        Connect packet = Connect.newConnection(protocolHash);
        ByteBuffer encoded = PacketCodec.encode(packet);

        assertEquals(34, encoded.remaining()); // 1 + 32 + 1
    }

    @Test
    void connect_reconnect_wireFormat()
    {
        byte[] protocolHash = new byte[32];
        byte[] sessionToken = new byte[16];
        Connect packet = Connect.reconnect(protocolHash, sessionToken, 0);
        ByteBuffer encoded = PacketCodec.encode(packet);

        assertEquals(54, encoded.remaining()); // 1 + 32 + 1 + 16 + 4
    }

    // ========== Accept ==========

    @Test
    void accept_roundTrip()
    {
        byte[] sessionToken = new byte[16];
        Arrays.fill(sessionToken, (byte) 0x44);

        Accept original = new Accept(sessionToken, 5000, 120000, 17);
        ByteBuffer encoded = PacketCodec.encode(original);
        Accept decoded = PacketCodec.decodeAccept(encoded);

        assertArrayEquals(original.sessionToken(), decoded.sessionToken());
        assertEquals(original.heartbeatIntervalMs(), decoded.heartbeatIntervalMs());
        assertEquals(original.sessionTimeoutMs(), decoded.sessionTimeoutMs());
        assertEquals(original.lastReceivedSeq(), decoded.lastReceivedSeq());
    }

    @Test
    void accept_wireFormat()
    {
        byte[] sessionToken = new byte[16];
        Accept packet = new Accept(sessionToken, 5000, 120000, 0);
        ByteBuffer encoded = PacketCodec.encode(packet);

        assertEquals(29, encoded.remaining()); // 1 + 16 + 4 + 4 + 4
    }

    // ========== Reject ==========

    @Test
    void reject_roundTrip()
    {
        Reject original = new Reject(Reject.RejectReason.PROTOCOL_MISMATCH, "Version mismatch");
        ByteBuffer encoded = PacketCodec.encode(original);
        Reject decoded = PacketCodec.decodeReject(encoded);

        assertEquals(original.reasonCode(), decoded.reasonCode());
        assertEquals(original.message(), decoded.message());
    }

    @Test
    void reject_allReasonCodes()
    {
        for (Reject.RejectReason reason : Reject.RejectReason.values())
        {
            Reject original = new Reject(reason, "Test message");
            ByteBuffer encoded = PacketCodec.encode(original);
            Reject decoded = PacketCodec.decodeReject(encoded);

            assertEquals(reason, decoded.reasonCode());
        }
    }

    @Test
    void reject_emptyMessage()
    {
        Reject original = new Reject(Reject.RejectReason.SERVER_FULL, "");
        ByteBuffer encoded = PacketCodec.encode(original);
        Reject decoded = PacketCodec.decodeReject(encoded);

        assertEquals("", decoded.message());
    }

    @Test
    void reject_unicodeMessage()
    {
        Reject original = new Reject(Reject.RejectReason.AUTHENTICATION_FAILED, "认证失败 🔒");
        ByteBuffer encoded = PacketCodec.encode(original);
        Reject decoded = PacketCodec.decodeReject(encoded);

        assertEquals(original.message(), decoded.message());
    }

    // ========== Data ==========

    @Test
    void data_unreliable_roundTrip()
    {
        byte[] payload = {0x01, 0x02, 0x03, 0x04};
        Data original = Data.unreliable(0x1234, payload);
        ByteBuffer encoded = PacketCodec.encode(original);
        Data decoded = PacketCodec.decodeData(encoded);

        assertFalse(decoded.reliable());
        assertTrue(decoded.sequence().isEmpty());
        assertTrue(decoded.ackSequence().isEmpty());
        assertEquals(0x1234, decoded.messageTypeId());
        assertArrayEquals(payload, decoded.payload());
    }

    @Test
    void data_reliable_roundTrip()
    {
        byte[] payload = {0x0A, 0x0B, 0x0C};
        Data original = Data.reliable(42, 0x5678, payload);
        ByteBuffer encoded = PacketCodec.encode(original);
        Data decoded = PacketCodec.decodeData(encoded);

        assertTrue(decoded.reliable());
        assertEquals(42, decoded.sequence().orElse(-1));
        assertTrue(decoded.ackSequence().isEmpty());
        assertEquals(0x5678, decoded.messageTypeId());
        assertArrayEquals(payload, decoded.payload());
    }

    @Test
    void data_withAck_roundTrip()
    {
        byte[] payload = {0x00};
        Data original = Data.reliable(100, 0x9ABC, payload).withAck(99);
        ByteBuffer encoded = PacketCodec.encode(original);
        Data decoded = PacketCodec.decodeData(encoded);

        assertTrue(decoded.reliable());
        assertEquals(100, decoded.sequence().orElse(-1));
        assertEquals(99, decoded.ackSequence().orElse(-1));
        assertEquals(0x9ABC, decoded.messageTypeId());
    }

    @Test
    void data_unreliable_wireFormat()
    {
        byte[] payload = {0x01, 0x02};
        Data packet = Data.unreliable(0x0001, payload);
        ByteBuffer encoded = PacketCodec.encode(packet);

        assertEquals(6, encoded.remaining()); // 1 + 1 + 2 + 2
        assertEquals(0x20, encoded.get()); // type
        assertEquals(0x00, encoded.get()); // flags (no reliable, no ack)
    }

    @Test
    void data_reliable_wireFormat()
    {
        byte[] payload = {0x01, 0x02};
        Data packet = Data.reliable(1, 0x0001, payload);
        ByteBuffer encoded = PacketCodec.encode(packet);

        assertEquals(10, encoded.remaining()); // 1 + 1 + 4 + 2 + 2
        assertEquals(0x20, encoded.get()); // type
        assertEquals(0x01, encoded.get()); // flags (reliable)
    }

    @Test
    void data_reliableWithAck_wireFormat()
    {
        byte[] payload = {0x01, 0x02};
        Data packet = Data.reliable(1, 0x0001, payload).withAck(0);
        ByteBuffer encoded = PacketCodec.encode(packet);

        assertEquals(14, encoded.remaining()); // 1 + 1 + 4 + 4 + 2 + 2
        assertEquals(0x20, encoded.get()); // type
        assertEquals(0x03, encoded.get()); // flags (reliable + has_ack)
    }

    @Test
    void data_emptyPayload()
    {
        byte[] payload = {};
        Data original = Data.unreliable(0x0001, payload);
        ByteBuffer encoded = PacketCodec.encode(original);
        Data decoded = PacketCodec.decodeData(encoded);

        assertArrayEquals(payload, decoded.payload());
    }

    @Test
    void data_largePayload()
    {
        byte[] payload = new byte[10000];
        Arrays.fill(payload, (byte) 0xFF);

        Data original = Data.reliable(1, 0x0001, payload);
        ByteBuffer encoded = PacketCodec.encode(original);
        Data decoded = PacketCodec.decodeData(encoded);

        assertArrayEquals(payload, decoded.payload());
    }

    // ========== Ack ==========

    @Test
    void ack_simple_roundTrip()
    {
        Ack original = Ack.single(42);
        ByteBuffer encoded = PacketCodec.encode(original);
        Ack decoded = PacketCodec.decodeAck(encoded);

        assertEquals(original.ackSequence(), decoded.ackSequence());
        assertEquals(0, decoded.ackBitmap());
    }

    @Test
    void ack_withBitmap_roundTrip()
    {
        Ack original = new Ack(100, 0b10101010);
        ByteBuffer encoded = PacketCodec.encode(original);
        Ack decoded = PacketCodec.decodeAck(encoded);

        assertEquals(100, decoded.ackSequence());
        assertEquals(0b10101010, decoded.ackBitmap());
    }

    @Test
    void ack_acknowledges()
    {
        Ack ack = new Ack(10, 0b00000101); // bits 0 and 2 set

        // Cumulative ack
        assertTrue(ack.acknowledges(10));
        assertTrue(ack.acknowledges(9));
        assertTrue(ack.acknowledges(0));

        // Selective acks: 11 (bit 0), 13 (bit 2)
        assertTrue(ack.acknowledges(11)); // 10 + 1 + 0
        assertFalse(ack.acknowledges(12)); // 10 + 1 + 1, bit 1 not set
        assertTrue(ack.acknowledges(13)); // 10 + 1 + 2

        // Beyond bitmap
        assertFalse(ack.acknowledges(43)); // 10 + 1 + 32, out of range
    }

    @Test
    void ack_wireFormat()
    {
        Ack packet = new Ack(0, 0);
        ByteBuffer encoded = PacketCodec.encode(packet);

        assertEquals(9, encoded.remaining()); // 1 + 4 + 4
    }

    // ========== Heartbeat ==========

    @Test
    void heartbeat_roundTrip()
    {
        Heartbeat original = new Heartbeat(1234567890123L);
        ByteBuffer encoded = PacketCodec.encode(original);
        Heartbeat decoded = PacketCodec.decodeHeartbeat(encoded);

        assertEquals(original.timestamp(), decoded.timestamp());
    }

    @Test
    void heartbeat_wireFormat()
    {
        Heartbeat packet = new Heartbeat(0);
        ByteBuffer encoded = PacketCodec.encode(packet);

        assertEquals(9, encoded.remaining()); // 1 + 8
        assertEquals(0x30, encoded.get()); // type
    }

    // ========== HeartbeatAck ==========

    @Test
    void heartbeatAck_roundTrip()
    {
        HeartbeatAck original = new HeartbeatAck(1000L, 2000L);
        ByteBuffer encoded = PacketCodec.encode(original);
        HeartbeatAck decoded = PacketCodec.decodeHeartbeatAck(encoded);

        assertEquals(original.echoTimestamp(), decoded.echoTimestamp());
        assertEquals(original.timestamp(), decoded.timestamp());
    }

    @Test
    void heartbeatAck_wireFormat()
    {
        HeartbeatAck packet = new HeartbeatAck(0, 0);
        ByteBuffer encoded = PacketCodec.encode(packet);

        assertEquals(17, encoded.remaining()); // 1 + 8 + 8
        assertEquals(0x31, encoded.get()); // type
    }

    // ========== Disconnect ==========

    @Test
    void disconnect_roundTrip()
    {
        Disconnect original = Disconnect.kicked("Bad behavior");
        ByteBuffer encoded = PacketCodec.encode(original);
        Disconnect decoded = PacketCodec.decodeDisconnect(encoded);

        assertEquals(original.reasonCode(), decoded.reasonCode());
        assertEquals(original.message(), decoded.message());
    }

    @Test
    void disconnect_allReasonCodes()
    {
        for (Disconnect.DisconnectCode code : Disconnect.DisconnectCode.values())
        {
            Disconnect original = new Disconnect(code, "Test");
            ByteBuffer encoded = PacketCodec.encode(original);
            Disconnect decoded = PacketCodec.decodeDisconnect(encoded);

            assertEquals(code, decoded.reasonCode());
        }
    }

    @Test
    void disconnect_factoryMethods()
    {
        assertEquals(Disconnect.DisconnectCode.NORMAL, Disconnect.normal("bye").reasonCode());
        assertEquals(Disconnect.DisconnectCode.KICKED, Disconnect.kicked("bad").reasonCode());
        assertEquals(Disconnect.DisconnectCode.PROTOCOL_ERROR, Disconnect.protocolError("err").reasonCode());
        assertEquals(Disconnect.DisconnectCode.SHUTDOWN, Disconnect.shutdown().reasonCode());
    }

    // ========== Generic decode ==========

    @Test
    void decode_dispatchesToCorrectDecoder()
    {
        // Test that the generic decode() method works for all types
        assertInstanceOf(ClientHello.class, PacketCodec.decode(
                PacketCodec.encode(new ClientHello(1, new byte[32]))));

        assertInstanceOf(ServerHello.class, PacketCodec.decode(
                PacketCodec.encode(new ServerHello(1, new byte[32], new byte[64]))));

        assertInstanceOf(Connect.class, PacketCodec.decode(
                PacketCodec.encode(Connect.newConnection(new byte[32]))));

        assertInstanceOf(Accept.class, PacketCodec.decode(
                PacketCodec.encode(new Accept(new byte[16], 5000, 120000, 0))));

        assertInstanceOf(Reject.class, PacketCodec.decode(
                PacketCodec.encode(new Reject(Reject.RejectReason.SERVER_FULL, "full"))));

        assertInstanceOf(Data.class, PacketCodec.decode(
                PacketCodec.encode(Data.unreliable(1, new byte[0]))));

        assertInstanceOf(Ack.class, PacketCodec.decode(
                PacketCodec.encode(Ack.single(0))));

        assertInstanceOf(Heartbeat.class, PacketCodec.decode(
                PacketCodec.encode(new Heartbeat(0))));

        assertInstanceOf(HeartbeatAck.class, PacketCodec.decode(
                PacketCodec.encode(new HeartbeatAck(0, 0))));

        assertInstanceOf(Disconnect.class, PacketCodec.decode(
                PacketCodec.encode(Disconnect.normal(""))));
    }

    // ========== PacketType ==========

    @Test
    void packetType_fromId()
    {
        assertEquals(PacketType.CLIENT_HELLO, PacketType.fromId(0x01));
        assertEquals(PacketType.SERVER_HELLO, PacketType.fromId(0x02));
        assertEquals(PacketType.CONNECT, PacketType.fromId(0x10));
        assertEquals(PacketType.ACCEPT, PacketType.fromId(0x11));
        assertEquals(PacketType.REJECT, PacketType.fromId(0x12));
        assertEquals(PacketType.DATA, PacketType.fromId(0x20));
        assertEquals(PacketType.ACK, PacketType.fromId(0x21));
        assertEquals(PacketType.HEARTBEAT, PacketType.fromId(0x30));
        assertEquals(PacketType.HEARTBEAT_ACK, PacketType.fromId(0x31));
        assertEquals(PacketType.DISCONNECT, PacketType.fromId(0x40));
    }

    @Test
    void packetType_unknownId_throws()
    {
        assertThrows(IllegalArgumentException.class, () -> PacketType.fromId(0xFF));
    }

    @Test
    void packetType_encryptedFlags()
    {
        assertFalse(PacketType.CLIENT_HELLO.isEncrypted());
        assertFalse(PacketType.SERVER_HELLO.isEncrypted());
        assertTrue(PacketType.CONNECT.isEncrypted());
        assertTrue(PacketType.ACCEPT.isEncrypted());
        assertTrue(PacketType.DATA.isEncrypted());
        assertTrue(PacketType.HEARTBEAT.isEncrypted());
        assertTrue(PacketType.DISCONNECT.isEncrypted());
    }

    // ========== Edge cases ==========

    @Test
    void peekType_doesNotConsumeBuffer()
    {
        ByteBuffer buffer = PacketCodec.encode(new Heartbeat(0));
        int positionBefore = buffer.position();

        PacketType type = PacketCodec.peekType(buffer);

        assertEquals(PacketType.HEARTBEAT, type);
        assertEquals(positionBefore, buffer.position());
    }

    @Test
    void decode_wrongType_throws()
    {
        ByteBuffer heartbeatBuffer = PacketCodec.encode(new Heartbeat(0));

        assertThrows(IllegalArgumentException.class, () ->
                PacketCodec.decodeClientHello(heartbeatBuffer));
    }
}
