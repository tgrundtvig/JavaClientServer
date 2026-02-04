package org.abstractica.clientserver.impl.serialization;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link DefaultProtocol}.
 */
class DefaultProtocolTest
{
    // ========== Test Message Types ==========

    sealed interface TestClientMessage permits LoginRequest, ChatMessage, MoveCommand {}
    sealed interface TestServerMessage permits LoginResponse, ChatBroadcast, PlayerPosition {}

    record LoginRequest(String username, String password) implements TestClientMessage {}
    record ChatMessage(String text) implements TestClientMessage {}
    record MoveCommand(int x, int y, boolean running) implements TestClientMessage {}

    record LoginResponse(boolean success, Optional<String> error) implements TestServerMessage {}
    record ChatBroadcast(String sender, String text) implements TestServerMessage {}
    record PlayerPosition(int playerId, int x, int y) implements TestServerMessage {}

    private DefaultProtocol protocol;

    @BeforeEach
    void setUp()
    {
        protocol = new DefaultProtocol.Builder()
                .clientMessages(TestClientMessage.class)
                .serverMessages(TestServerMessage.class)
                .build();
    }

    // ========== Builder Tests ==========

    @Test
    void build_success()
    {
        assertNotNull(protocol);
        assertNotNull(protocol.getHash());
        assertEquals(64, protocol.getHash().length()); // SHA-256 = 64 hex chars
    }

    @Test
    void build_missingClientMessages_throws()
    {
        assertThrows(IllegalStateException.class, () ->
                new DefaultProtocol.Builder()
                        .serverMessages(TestServerMessage.class)
                        .build());
    }

    @Test
    void build_missingServerMessages_throws()
    {
        assertThrows(IllegalStateException.class, () ->
                new DefaultProtocol.Builder()
                        .clientMessages(TestClientMessage.class)
                        .build());
    }

    @Test
    void build_notSealed_throws()
    {
        assertThrows(IllegalArgumentException.class, () ->
                new DefaultProtocol.Builder()
                        .clientMessages(String.class) // Not sealed
                        .serverMessages(TestServerMessage.class)
                        .build());
    }

    // ========== Type ID Assignment Tests ==========

    @Test
    void getTypeId_clientMessages_inRange()
    {
        // Client IDs should be 0x0000 - 0x7FFF
        int loginId = protocol.getTypeId(LoginRequest.class);
        int chatId = protocol.getTypeId(ChatMessage.class);
        int moveId = protocol.getTypeId(MoveCommand.class);

        assertTrue(loginId >= 0 && loginId <= 0x7FFF);
        assertTrue(chatId >= 0 && chatId <= 0x7FFF);
        assertTrue(moveId >= 0 && moveId <= 0x7FFF);
    }

    @Test
    void getTypeId_serverMessages_inRange()
    {
        // Server IDs should be 0x8000 - 0xFFFF
        int loginRespId = protocol.getTypeId(LoginResponse.class);
        int chatBcastId = protocol.getTypeId(ChatBroadcast.class);
        int positionId = protocol.getTypeId(PlayerPosition.class);

        assertTrue(loginRespId >= 0x8000 && loginRespId <= 0xFFFF);
        assertTrue(chatBcastId >= 0x8000 && chatBcastId <= 0xFFFF);
        assertTrue(positionId >= 0x8000 && positionId <= 0xFFFF);
    }

    @Test
    void getTypeId_sortedByFQName()
    {
        // Types should be sorted by fully-qualified name
        // ChatMessage < LoginRequest < MoveCommand (alphabetically)
        int chatId = protocol.getTypeId(ChatMessage.class);
        int loginId = protocol.getTypeId(LoginRequest.class);
        int moveId = protocol.getTypeId(MoveCommand.class);

        assertTrue(chatId < loginId);
        assertTrue(loginId < moveId);
    }

    @Test
    void getTypeId_unknownType_throws()
    {
        record UnknownMessage(int x) {}
        assertThrows(IllegalArgumentException.class, () ->
                protocol.getTypeId(UnknownMessage.class));
    }

    @Test
    void getMessageClass_roundTrip()
    {
        int id = protocol.getTypeId(LoginRequest.class);
        Class<?> clazz = protocol.getMessageClass(id);
        assertEquals(LoginRequest.class, clazz);
    }

    @Test
    void getMessageClass_unknownId_throws()
    {
        assertThrows(IllegalArgumentException.class, () ->
                protocol.getMessageClass(0x7FFF));
    }

    @Test
    void isClientMessage_correct()
    {
        int clientId = protocol.getTypeId(LoginRequest.class);
        int serverId = protocol.getTypeId(LoginResponse.class);

        assertTrue(protocol.isClientMessage(clientId));
        assertFalse(protocol.isClientMessage(serverId));
    }

    @Test
    void isServerMessage_correct()
    {
        int clientId = protocol.getTypeId(LoginRequest.class);
        int serverId = protocol.getTypeId(LoginResponse.class);

        assertFalse(protocol.isServerMessage(clientId));
        assertTrue(protocol.isServerMessage(serverId));
    }

    // ========== Typed Decode Tests ==========

    @Test
    void decodeClientMessage_returnsTypedMessage()
    {
        LoginRequest original = new LoginRequest("user", "pass");
        byte[] encoded = protocol.encodeMessage(original);

        // Decode as typed client message - can use directly with pattern matching
        TestClientMessage msg = protocol.decodeClientMessage(encoded);

        assertInstanceOf(LoginRequest.class, msg);
        assertEquals(original, msg);
    }

    @Test
    void decodeClientMessage_worksWithPatternMatching()
    {
        ChatMessage original = new ChatMessage("Hello!");
        byte[] encoded = protocol.encodeMessage(original);

        TestClientMessage msg = protocol.decodeClientMessage(encoded);

        // Pattern matching works directly
        String result = switch (msg)
        {
            case LoginRequest r -> "login:" + r.username();
            case ChatMessage c -> "chat:" + c.text();
            case MoveCommand m -> "move:" + m.x();
        };

        assertEquals("chat:Hello!", result);
    }

    @Test
    void decodeClientMessage_rejectsServerMessage()
    {
        LoginResponse serverMsg = new LoginResponse(true, Optional.empty());
        byte[] encoded = protocol.encodeMessage(serverMsg);

        assertThrows(IllegalArgumentException.class, () ->
                protocol.decodeClientMessage(encoded));
    }

    @Test
    void decodeServerMessage_returnsTypedMessage()
    {
        LoginResponse original = new LoginResponse(true, Optional.of("Welcome"));
        byte[] encoded = protocol.encodeMessage(original);

        TestServerMessage msg = protocol.decodeServerMessage(encoded);

        assertInstanceOf(LoginResponse.class, msg);
        assertEquals(original, msg);
    }

    @Test
    void decodeServerMessage_worksWithPatternMatching()
    {
        PlayerPosition original = new PlayerPosition(42, 100, 200);
        byte[] encoded = protocol.encodeMessage(original);

        TestServerMessage msg = protocol.decodeServerMessage(encoded);

        String result = switch (msg)
        {
            case LoginResponse r -> "login:" + r.success();
            case ChatBroadcast b -> "chat:" + b.text();
            case PlayerPosition p -> "pos:" + p.x() + "," + p.y();
        };

        assertEquals("pos:100,200", result);
    }

    @Test
    void decodeServerMessage_rejectsClientMessage()
    {
        LoginRequest clientMsg = new LoginRequest("user", "pass");
        byte[] encoded = protocol.encodeMessage(clientMsg);

        assertThrows(IllegalArgumentException.class, () ->
                protocol.decodeServerMessage(encoded));
    }

    @Test
    void peekTypeId_returnsCorrectId()
    {
        LoginRequest msg = new LoginRequest("user", "pass");
        byte[] encoded = protocol.encodeMessage(msg);

        int typeId = protocol.peekTypeId(encoded);

        assertEquals(protocol.getTypeId(LoginRequest.class), typeId);
    }

    @Test
    void peekTypeId_tooShort_throws()
    {
        assertThrows(IllegalArgumentException.class, () ->
                protocol.peekTypeId(new byte[1]));
    }

    // ========== Hash Tests ==========

    @Test
    void getHash_deterministic()
    {
        DefaultProtocol protocol2 = new DefaultProtocol.Builder()
                .clientMessages(TestClientMessage.class)
                .serverMessages(TestServerMessage.class)
                .build();

        assertEquals(protocol.getHash(), protocol2.getHash());
    }

    // For hash difference test
    sealed interface OtherClientMessage permits OtherRequest {}
    record OtherRequest(int value) implements OtherClientMessage {}

    @Test
    void getHash_differentTypes_differentHash()
    {
        DefaultProtocol otherProtocol = new DefaultProtocol.Builder()
                .clientMessages(OtherClientMessage.class)
                .serverMessages(TestServerMessage.class)
                .build();

        assertNotEquals(protocol.getHash(), otherProtocol.getHash());
    }

    @Test
    void getHashBytes_correctLength()
    {
        byte[] hashBytes = protocol.getHashBytes();
        assertEquals(32, hashBytes.length); // SHA-256 = 32 bytes
    }

    // ========== Encode/Decode Tests ==========

    @Test
    void encodeMessage_decode_roundTrip()
    {
        LoginRequest original = new LoginRequest("user", "pass");
        byte[] encoded = protocol.encodeMessage(original);
        Record decoded = protocol.decodeMessage(encoded);

        assertEquals(original, decoded);
    }

    @Test
    void encodeMessage_includesTypeId()
    {
        LoginRequest message = new LoginRequest("user", "pass");
        byte[] encoded = protocol.encodeMessage(message);

        // First 2 bytes are type ID
        int typeId = ((encoded[0] & 0xFF) << 8) | (encoded[1] & 0xFF);
        assertEquals(protocol.getTypeId(LoginRequest.class), typeId);
    }

    @Test
    void decodeMessage_clientMessages()
    {
        ChatMessage original = new ChatMessage("Hello!");
        byte[] encoded = protocol.encodeMessage(original);
        Record decoded = protocol.decodeMessage(encoded);

        assertInstanceOf(ChatMessage.class, decoded);
        assertEquals("Hello!", ((ChatMessage) decoded).text());
    }

    @Test
    void decodeMessage_serverMessages()
    {
        LoginResponse original = new LoginResponse(true, Optional.empty());
        byte[] encoded = protocol.encodeMessage(original);
        Record decoded = protocol.decodeMessage(encoded);

        assertInstanceOf(LoginResponse.class, decoded);
        LoginResponse response = (LoginResponse) decoded;
        assertTrue(response.success());
        assertTrue(response.error().isEmpty());
    }

    @Test
    void decodeMessage_withOptional()
    {
        LoginResponse original = new LoginResponse(false, Optional.of("Invalid password"));
        byte[] encoded = protocol.encodeMessage(original);
        Record decoded = protocol.decodeMessage(encoded);

        assertInstanceOf(LoginResponse.class, decoded);
        LoginResponse response = (LoginResponse) decoded;
        assertFalse(response.success());
        assertEquals("Invalid password", response.error().orElse(""));
    }

    @Test
    void decodeMessage_complexType()
    {
        MoveCommand original = new MoveCommand(100, 200, true);
        byte[] encoded = protocol.encodeMessage(original);
        Record decoded = protocol.decodeMessage(encoded);

        assertEquals(original, decoded);
    }

    // ========== Nested Sealed Interface Tests ==========

    sealed interface NestedClientMessage permits SimpleMessage, NestedGroup {}
    sealed interface NestedGroup extends NestedClientMessage permits GroupA, GroupB {}
    record SimpleMessage(int value) implements NestedClientMessage {}
    record GroupA(String a) implements NestedGroup {}
    record GroupB(String b) implements NestedGroup {}

    sealed interface DummyServerMessage permits DummyResponse {}
    record DummyResponse(int x) implements DummyServerMessage {}

    @Test
    void build_nestedSealedInterfaces()
    {
        DefaultProtocol nestedProtocol = new DefaultProtocol.Builder()
                .clientMessages(NestedClientMessage.class)
                .serverMessages(DummyServerMessage.class)
                .build();

        // All leaf records should be registered
        assertDoesNotThrow(() -> nestedProtocol.getTypeId(SimpleMessage.class));
        assertDoesNotThrow(() -> nestedProtocol.getTypeId(GroupA.class));
        assertDoesNotThrow(() -> nestedProtocol.getTypeId(GroupB.class));
    }

    @Test
    void nestedSealedInterfaces_encodeDecodeRoundTrip()
    {
        DefaultProtocol nestedProtocol = new DefaultProtocol.Builder()
                .clientMessages(NestedClientMessage.class)
                .serverMessages(DummyServerMessage.class)
                .build();

        GroupA original = new GroupA("test");
        byte[] encoded = nestedProtocol.encodeMessage(original);
        Record decoded = nestedProtocol.decodeMessage(encoded);

        assertEquals(original, decoded);
    }

    // ========== Validation Tests ==========

    sealed interface InvalidMessage permits InvalidRecord {}
    record InvalidRecord(Thread invalid) implements InvalidMessage {} // Thread is not supported

    @Test
    void build_unsupportedType_throws()
    {
        assertThrows(IllegalArgumentException.class, () ->
                new DefaultProtocol.Builder()
                        .clientMessages(InvalidMessage.class)
                        .serverMessages(TestServerMessage.class)
                        .build());
    }

    // ========== Accessor Tests ==========

    @Test
    void getClientMessageType_returnsCorrectType()
    {
        assertEquals(TestClientMessage.class, protocol.getClientMessageType());
    }

    @Test
    void getServerMessageType_returnsCorrectType()
    {
        assertEquals(TestServerMessage.class, protocol.getServerMessageType());
    }
}
