# Requirements Specification

## Overview

JavaClientServer is a library for building client-server applications in Java. It provides type-safe message passing over an encrypted UDP transport with built-in reliability, session management, and reconnection support.

### Target Use Cases

- Multiplayer games (from text adventures to Minecraft-scale)
- Real-time collaborative applications
- Any application requiring persistent bidirectional communication

### Target Platform

- **Java 25**: Leverages modern features (records, sealed interfaces, pattern matching)
- **Pure Java**: No JNI, no native dependencies, runs anywhere Java runs

### Design Principles

- **Type safety**: Messages are Java records with sealed interface hierarchies
- **Protocol independence**: Application protocols have no library dependency
- **Mandatory security**: All traffic is encrypted, no insecure mode
- **Single responsibility**: Library handles transport, application handles meaning
- **Simplicity**: Reliable messages are always ordered, one threading model
- **Clarity**: Clean, readable implementation following project coding guidelines

## Core Concepts

### Message

An immutable Java record representing data sent between client and server. Messages are grouped into sealed interface hierarchies defined by the application.

```java
public sealed interface ClientMessage permits Join, Move, Chat {}
public record Join(String playerName) implements ClientMessage {}
public record Move(int x, int y) implements ClientMessage {}
```

### Protocol

A set of message types that define the contract between client and server. The protocol is a pure Java module with no library dependencies.

```java
Protocol protocol = Protocol.builder()
    .clientMessages(ClientMessage.class)
    .serverMessages(ServerMessage.class)
    .build();
```

The names `ClientMessage` and `ServerMessage` are the application's choice. Could be named `Request`/`Response`, `Inbound`/`Outbound`, `ToServer`/`ToClient`, etc. The library only needs to know which direction each sealed hierarchy represents.

### Session

A logical connection between client and server that survives physical disconnects. Sessions have:

- Unique identifier (session token)
- Configurable timeout
- Sequence numbers for message ordering
- Associated application state (managed by application)

### Connection

A physical network link (UDP socket). Connections can drop and reconnect while the session remains active.

## Transport Layer

### UDP with Custom Reliability

The library uses UDP as the underlying transport with a custom reliability layer built on top.

#### Rationale

- Control over what needs reliability (chat: yes, position updates: maybe not)
- No head-of-line blocking
- Full control for debugging and optimization
- Study KCP algorithm for implementation guidance

### Delivery Modes

| Mode | Guarantees | Use Case |
|------|-----------|----------|
| Unreliable | None - may be lost, duplicated, reordered | Position updates, ephemeral state |
| Reliable | Delivered exactly once, in order | Chat, inventory, important events |

```java
session.send(positionUpdate, Delivery.UNRELIABLE);
session.send(inventoryChange, Delivery.RELIABLE);

// Default delivery mode is RELIABLE
session.send(chatMessage);  // equivalent to Delivery.RELIABLE
```

**Note**: Reliable always implies ordered. There is no "reliable but unordered" mode. This simplifies the implementation and covers the vast majority of use cases. If out-of-order reliable delivery were needed, it would require parallel sequence streams - unnecessary complexity for this library.

### Transport vs Application Messages

The library maintains two distinct message layers:

**Transport layer (library-internal, invisible to application):**
- Acknowledgments
- Heartbeat/keepalive
- Encryption handshake
- Sequence number synchronization
- Session token exchange

**Application layer (user-defined protocol):**
- Game messages (Move, Attack, Chat)
- All messages defined in the protocol module
- Everything the application sends and receives

This separation keeps the protocol clean. Application code never sees transport internals.

### Sequence Numbers

Reliable messages use sequence numbers to provide:

- **Ordering**: Messages processed in send order
- **Deduplication**: Same message not processed twice
- **Gap detection**: Missing messages trigger retransmission
- **Reconnect resync**: Both sides know where to resume

Each session maintains:
- Outbound sequence number (increments with each reliable send)
- Last received sequence number from peer
- Queue of unacknowledged outbound messages

### Retransmission

Unacknowledged reliable messages are retransmitted until:
- Acknowledgment received (success)
- Connection declared dead (failure)

Acknowledgments can be piggybacked on outgoing data packets.

### RTT Estimation

The reliability layer tracks round-trip time to:
- Determine appropriate retransmission timeouts
- Avoid premature retransmits on high-latency connections
- Adapt to changing network conditions

## Message System

### Type-Safe Messages

Messages are Java records organized in sealed interface hierarchies:

```java
// Client → Server
public sealed interface ClientMessage permits Join, Move, Chat, Quit {}
public record Join(String playerName) implements ClientMessage {}
public record Move(int x, int y) implements ClientMessage {}
public record Chat(String text) implements ClientMessage {}
public record Quit() implements ClientMessage {}

// Server → Client
public sealed interface ServerMessage permits Welcome, StateUpdate, ChatBroadcast {}
public record Welcome(String sessionId, GameState initial) implements ServerMessage {}
public record StateUpdate(GameState state) implements ServerMessage {}
public record ChatBroadcast(String playerId, String text) implements ServerMessage {}
```

### Supported Field Types

- Primitives: `int`, `long`, `double`, `boolean`, etc.
- Strings
- Other records (nested)
- Lists of supported types
- Enums
- Optional (with presence byte on wire)

### Binary Serialization

Messages are serialized to a compact binary format:

```
[type_id: 2 bytes][field_1][field_2]...[field_n]
```

- **Type ID**: Identifies the record type (assigned at startup)
- **Fields**: Serialized in record component order, no field names on wire


#### Serializer Generation

Serializers are built at startup via reflection:

1. Scan sealed interface hierarchy
2. Discover all permitted record types
3. For each record, inspect components
4. Generate efficient serializer/deserializer
5. Assign type IDs
6. Cache for runtime use

No compile-time code generation. No schema files.

### Type Registry

Both client and server must have the same protocol (shared module). Type IDs are derived from the protocol structure, ensuring consistency.

### Startup Validation

When building the protocol, the library validates:
- All permitted types in sealed hierarchies are records
- All nested record types are registered
- All field types are supported
- No circular references that can't be serialized

Validation errors fail fast at startup with clear error messages, not at runtime when first message is sent.

## Protocol Versioning

### Strict Matching

Client and server must have identical protocol versions. No backward compatibility complexity.

### Version Hash

A hash of the protocol structure (all types + their fields) is computed at startup. During handshake:

1. Server sends protocol hash
2. Client compares to its own hash
3. Mismatch → connection rejected with "protocol mismatch" error

This catches:
- Added/removed message types
- Added/removed/changed fields
- Field type changes

### Rationale

- Games typically require client updates anyway
- Supporting old versions means maintaining old behavior forever
- Strict matching eliminates subtle compatibility bugs

## Session Management

### Session Lifecycle

```
┌─────────────────────────────────────────────────────────────┐
│                                                             │
│  ┌──────────┐    ┌───────────┐    ┌────────────────────┐   │
│  │ Connect  │───►│  Active   │───►│   Disconnected     │   │
│  └──────────┘    └───────────┘    └────────────────────┘   │
│                        │                    │               │
│                        │                    ▼               │
│                        │          ┌────────────────────┐   │
│                        │          │ Reconnect (resume) │   │
│                        │          └────────────────────┘   │
│                        │                    │               │
│                        │◄───────────────────┘               │
│                        │                                    │
│                        ▼                                    │
│               ┌────────────────┐                           │
│               │    Expired     │                           │
│               └────────────────┘                           │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

### Session Token

- Cryptographically random, 128+ bits
- Assigned on first connection
- Client stores locally
- Presented on reconnect to resume session

### Session Timeout

How long a session survives without an active connection.

- **Configurable**: Application decides based on use case
- **Default**: 2 minutes
- **Range**: From seconds (fast-paced PvP) to minutes (casual games)

### Session Operations

```java
session.send(message, Delivery.RELIABLE);
session.close();
session.close("Reason for disconnect");
session.getId();
session.setAttachment(playerState);  // attach application data
```

### Server Operations

```java
// Broadcast to all connected sessions
server.broadcast(message);
server.broadcast(message, Delivery.UNRELIABLE);

// Get all active sessions
Collection<Session> sessions = server.getSessions();

// Stop accepting new connections (for graceful shutdown)
server.stopAcceptingConnections();

// Close server
server.close();
```

### Client Operations

```java
// Connect to server
client.connect();

// Disconnect
client.disconnect();

// Send message to server
client.send(message);
client.send(message, Delivery.UNRELIABLE);
```

### Reconnection Flow

1. Client detects connection loss
2. Library notifies application via `onDisconnected` callback
3. Application decides whether to reconnect (e.g., based on reason)
4. Application calls `client.connect()` to attempt reconnection
5. Library automatically sends stored session token
6. Server validates token, finds existing session
7. Both sides exchange last-received sequence numbers
8. Unacknowledged messages are retransmitted
9. Library notifies application via `onReconnected` callback
10. Session continues seamlessly

**Note**: The library stores the session token internally. Application does not need to manage token persistence between reconnection attempts within the same process. For persistence across application restarts, application would need to implement its own token storage.

### Message Handling During Disconnect

**Server → Client (outbound queue):**
- Reliable messages queued (bounded, configurable size)
- Unreliable messages dropped (stale anyway)
- On reconnect, queued messages delivered

**Client → Server (retransmission):**
- Unacknowledged reliable messages retained
- On reconnect, retransmitted based on sequence numbers

## Connection Health

### Heartbeat

Application-level ping/pong to detect dead connections faster than TCP keepalive (which can take minutes to detect a dead connection).

- **Interval**: Configurable, default 5 seconds
- **Timeout**: Typically 2-3 missed heartbeats (configurable)
- No heartbeat received within expected window → connection declared dead
- Heartbeat is transport-internal, not visible to application
- Heartbeats can piggyback on data packets to reduce overhead

### Ghost Connections

When client reconnects before server detects old connection is dead:

- **Rule**: Newest connection wins
- Old connection immediately terminated
- Session bound to new connection
- No duplicate session state

## Security

### Mandatory Encryption

All traffic is encrypted. No unencrypted mode.

#### Rationale

- Public wifi is ubiquitous
- Session tokens would be sniffable without encryption
- Performance impact is negligible (microseconds per packet)
- "Add encryption later" usually means never

### Encryption Protocol

```
1. Client → Server: Client ephemeral ECDH public key
2. Server → Client: Server ephemeral ECDH public key + signature
3. Both derive shared secret from ECDH
4. All subsequent traffic encrypted with ChaCha20-Poly1305
```

### Server Authentication

- Server has long-term key pair
- Server signs its ephemeral key during handshake
- Server's public key distributed with client (embedded in game)
- Client verifies signature → prevents MITM

### Properties

- **Encryption**: Traffic unreadable to eavesdroppers
- **Authentication**: Server identity verified
- **Forward secrecy**: Ephemeral keys → past traffic safe if keys later compromised
- **Integrity**: AEAD cipher detects tampering

### Client Authentication

- Not cryptographic at handshake level
- Client sends credentials (username/password, token) over encrypted channel
- Server validates and creates/resumes session
- Standard approach for games

### Key Management

The library provides utilities for generating and exporting server key pairs. Server private key should be stored securely. Public key is embedded in client application.

## Error Handling

### Design Principles

- No silent failures
- Clear lifecycle callbacks
- Application decides how to respond
- Protocol errors are fatal

### Lifecycle Callbacks

#### Client-Side

```java
client.onConnected(session -> { ... });
client.onConnectionFailed(reason -> { ... });
client.onDisconnected((session, reason) -> { ... });
client.onReconnected(session -> { ... });
client.onHandlerError((session, message, exception) -> { ... });
```

#### Server-Side

```java
server.onSessionStarted(session -> { ... });
server.onSessionDisconnected((session, reason) -> { ... });
server.onSessionReconnected(session -> { ... });
server.onSessionExpired(session -> { ... });
server.onHandlerError((session, message, exception) -> { ... });
```

### Disconnect Reasons

Sealed type for pattern matching:

```java
sealed interface DisconnectReason {
    record NetworkError(IOException cause) implements DisconnectReason {}
    record Timeout() implements DisconnectReason {}
    record KickedByServer(String message) implements DisconnectReason {}
    record ProtocolError(String details) implements DisconnectReason {}
    record ServerShutdown() implements DisconnectReason {}
}
```

Application can handle each case with exhaustive pattern matching:

```java
client.onDisconnected((session, reason) -> {
    switch (reason) {
        case NetworkError(var cause) -> log.warn("Network error", cause);
        case Timeout() -> showReconnecting();
        case KickedByServer(var msg) -> showKickMessage(msg);
        case ProtocolError(var details) -> showUpdateRequired();
        case ServerShutdown() -> showServerDown();
    }
});
```

### Handler Errors

If application message handler throws exception:
- Library catches it
- Logs via SLF4J
- Invokes error callback: `onHandlerError(session, message, exception)`
- Continues processing other messages

One buggy handler should not crash the server.

### Protocol Errors

Unknown message type or deserialization failure:
- Indicates version mismatch or malformed data
- Connection terminated immediately
- Surfaced as `DisconnectReason.ProtocolError`
- Fail fast, don't attempt recovery

### Send Backpressure

```java
boolean queued = session.send(message, Delivery.RELIABLE);
```

Returns `false` if reliable queue is full. This indicates receiver is not acknowledging - connection is effectively dead. Application can react accordingly.

### Message Size Limits

Messages that exceed the maximum size are rejected:
- Configurable maximum message size (default: reasonable limit for UDP)
- Large messages should be chunked at application level
- Attempting to send oversized message throws exception or returns false

## Threading Model

### Library Responsibility

- Manages network I/O threads internally
- Delivers messages to application handlers
- Handler called from network thread

### Application Responsibility

- Decides what to do in handlers
- If thread safety needed, application queues messages
- If immediate processing okay, handle directly

### Contract

```
Inbound:  Handlers called from network thread(s)
Outbound: session.send() is thread-safe, callable from any thread
```

### Handler Registration

Type-safe message handlers are registered per message type:

```java
// Server-side handlers
server.on(Join.class, (session, msg) -> {
    // Handle player join
});

server.on(PlayerMove.class, (session, msg) -> {
    // Handle movement
});

// Client-side handlers
client.on(Welcome.class, (session, msg) -> {
    // Handle welcome from server
});

client.on(StateUpdate.class, (session, msg) -> {
    // Handle game state update
});
```

The library deserializes the message and dispatches to the correct handler with the proper type. No casting in application code.

### Example Patterns

```java
// Game with tick loop - queue for later processing
server.on(PlayerMove.class, (session, msg) -> {
    gameLoop.enqueue(session, msg);
});

// Chat server - process immediately
server.on(ChatMessage.class, (session, msg) -> {
    broadcast(msg);
});
```

## Configuration

### Server Builder

```java
Server server = Server.builder()
    .port(7777)                                    // required
    .protocol(protocol)                            // required
    .privateKey(serverPrivateKey)                  // required
    .bindAddress("0.0.0.0")                        // optional, default: all interfaces
    .maxConnections(10000)                         // optional, default: unlimited
    .sessionTimeout(Duration.ofMinutes(5))         // optional, default: 2 minutes
    .heartbeatInterval(Duration.ofSeconds(5))      // optional, default: 5 seconds
    .maxReliableQueueSize(1000)                    // optional, has default
    .maxMessageSize(65536)                         // optional, has default
    .build();
```

### Client Builder

```java
Client client = Client.builder()
    .serverAddress("game.example.com", 7777)       // required
    .protocol(protocol)                            // required
    .serverPublicKey(serverPublicKey)              // required (for verification)
    .build();
```

- **Required parameters**: Build fails if missing
- **Optional parameters**: Sensible defaults
- **Discoverable**: IDE autocomplete shows options

### Wire-Negotiated Parameters

Some configuration is shared with clients during handshake:

```java
record ConnectionParameters(
    String protocolHash,
    Duration heartbeatInterval,
    Duration sessionTimeout
) {}
```

Server derives this from its configuration and sends during handshake.

## Observability

### Metrics

Pollable stats object:

```java
ServerStats stats = server.getStats();
stats.activeConnections();
stats.activeSessions();
stats.messagesPerSecond();
stats.bytesPerSecond();
stats.retransmitRate();
stats.averageRtt();
```

No external dependencies. Application polls and pushes to monitoring system of choice.

Client has access to similar connection stats.

### Logging

SLF4J facade:

- Application provides implementation (Logback, Log4j2, etc.)
- Library logs at appropriate levels:
  - ERROR: Failures requiring attention
  - WARN: Unexpected but handled
  - INFO: Significant events (connections, sessions)
  - DEBUG: Packet-level detail

### External Dependencies

The library has minimal external dependencies:

- **SLF4J API** (~40KB): Logging facade, de facto standard in Java ecosystem
- No other runtime dependencies

## Testing Support

Separate `clientserver-test` module (optional dependency).

### Mock Session

For unit testing handlers without networking:

```java
MockSession session = MockSession.create();
gameHandler.onMessage(session, new PlayerMove(5, 10));

assertThat(session.getSentMessages())
    .contains(new MoveConfirmed(5, 10));
```

### In-Memory Transport

For integration testing without real network:

```java
InMemoryServer server = InMemoryServer.create(protocol);
InMemoryClient client = server.connectClient();

client.send(new Join("Alice"));
// Immediate, synchronous, same thread
```

Full library behavior but no sockets, no threads, deterministic.

## Module Structure

```
clientserver/
├── clientserver-api/          # Core interfaces
├── clientserver-impl/         # Implementation
├── clientserver-test/         # Testing utilities (optional)
└── demo/
    ├── demo-protocol/         # Example message types
    ├── demo-server/           # Example server
    └── demo-client/           # Example client
```

### Module Dependencies

```
demo-protocol   → (none)
demo-server     → clientserver-impl, demo-protocol
demo-client     → clientserver-impl, demo-protocol
clientserver-impl → clientserver-api
clientserver-test → clientserver-api
```

### Protocol Independence

The `demo-protocol` module (and user protocol modules) have **zero library dependencies**. They contain only:

- Sealed interfaces defining message categories
- Records defining message types
- Pure Java, pure domain

### User Project Structure

Users should structure their projects similarly to the demo:

```
myapp/
├── myapp-protocol/       # Shared message types (no dependencies)
├── myapp-server/         # Server application
│   └── depends on: clientserver-impl, myapp-protocol
└── myapp-client/         # Client application
    └── depends on: clientserver-impl, myapp-protocol
```

This structure ensures:
- Protocol is reusable and independent
- Client and server share exact same message definitions
- Library is an implementation detail, not a foundation

## Demo Application

A simple multiplayer demonstration showing all library features.

### Demo Protocol

```java
// Client → Server
sealed interface ClientMessage permits Join, Ready, Move, Chat {}
record Join(String playerName) implements ClientMessage {}
record Ready() implements ClientMessage {}
record Move(int x, int y) implements ClientMessage {}
record Chat(String text) implements ClientMessage {}

// Server → Client
sealed interface ServerMessage permits Welcome, PlayerJoined, PlayerLeft,
                                         GameStarted, StateUpdate, ChatBroadcast {}
record Welcome(String playerId, List<Player> players) implements ServerMessage {}
record PlayerJoined(Player player) implements ServerMessage {}
record PlayerLeft(String playerId) implements ServerMessage {}
record GameStarted(GameState initial) implements ServerMessage {}
record StateUpdate(GameState state) implements ServerMessage {}
record ChatBroadcast(String playerId, String text) implements ServerMessage {}
```

### Demonstrated Features

- Connection and session lifecycle
- Reconnection with state preservation
- Reliable messaging (chat, join/leave)
- Unreliable messaging (position updates)
- Broadcast to all clients
- Targeted messages to specific client

### Scope

Minimal game logic - players as dots on a grid. Enough to demonstrate the library, simple enough to understand quickly.

## Graceful Shutdown

### Primitives Provided

```java
server.stopAcceptingConnections();  // No new connections
server.broadcast(message);          // Notify existing clients
server.close();                     // Force close remaining
```

### Application Responsibility

The shutdown choreography is application-specific:

```java
server.stopAcceptingConnections();
server.broadcast(new ShutdownWarning(30));
// Wait for games to finish or save
Thread.sleep(30_000);
server.close();
```

No built-in shutdown message type - this is application-level.

## Implementation Notes

### Reliability Layer

Study the KCP algorithm for guidance on implementing the reliability layer. The goal is to understand proven patterns, then implement cleanly in Java following project coding guidelines.

### Suggested Implementation Order

1. Basic UDP send/receive
2. Add encryption
3. Add reliability (ack/retry)
4. Add session management
5. Add record serialization

## Future Considerations

The following may be considered for future versions but are not in initial scope:

- **Session export/import**: Serialize session state for migration between servers
- **Multiple delivery channels**: Parallel reliable streams for independent message ordering

## Non-Goals

The following are explicitly out of scope:

- **Distributed server clustering**: Single server instance only
- **Game loop / tick system**: Application provides this
- **State synchronization strategies**: Application decides
- **Interest management**: Application decides what to send to whom
- **Matchmaking**: Separate service
- **Persistence**: Sessions are in-memory only
- **Web client support**: Java clients only
