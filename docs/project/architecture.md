# Architecture

## Module Structure

### Library Modules

```
clientserver/
├── clientserver-api/     # Interfaces, records, enums (exported)
├── clientserver-impl/    # Default implementation (not exported)
└── clientserver-test/    # Testing utilities (optional dependency)
```

**clientserver-api** — Public contract
- All interfaces, records, and enums that define the library API
- No implementation classes
- Exported to consumers

**clientserver-impl** — Default implementation
- Implements all interfaces from clientserver-api
- Internal classes (DefaultServer, DefaultClient, etc.)
- Not exported; consumers depend only on API

**clientserver-test** — Testing support
- MockSession, InMemoryServer, InMemoryClient
- Optional dependency for consumer test code
- Depends on clientserver-api only

### Demo Modules

```
demo/
├── demo-protocol/    # Message types (no dependencies)
├── demo-server/      # Server application
└── demo-client/      # Client application
```

**demo-protocol** — Pure protocol definition
- Sealed interfaces and records only
- **Zero dependencies** (not even clientserver-api)
- Shareable between client and server

**demo-server** / **demo-client** — Applications
- Depend on clientserver-impl and demo-protocol
- Demonstrate library usage patterns

### Module Dependencies

```
demo-protocol    → (none)
clientserver-api → (none, except SLF4J)
clientserver-impl → clientserver-api
clientserver-test → clientserver-api
demo-server      → clientserver-impl, demo-protocol
demo-client      → clientserver-impl, demo-protocol
```

### JPMS Module Info

```java
// clientserver-api
module clientserver.api
{
    requires org.slf4j;
    exports dk.cloudware.clientserver;
}

// clientserver-impl
module clientserver.impl
{
    requires clientserver.api;
    // dk.cloudware.clientserver.impl NOT exported
}

// clientserver-test
module clientserver.test
{
    requires clientserver.api;
    exports dk.cloudware.clientserver.test;
}

// demo-protocol (no module dependencies)
module demo.protocol
{
    exports dk.cloudware.demo.protocol;
}
```

## Package Structure

### clientserver-api

```
dk.cloudware.clientserver/
├── Server                    # Server interface
├── ServerFactory             # Creates Server instances
├── Client                    # Client interface
├── ClientFactory             # Creates Client instances
├── Session                   # Session interface
├── Protocol                  # Protocol definition
├── Delivery                  # enum: RELIABLE, UNRELIABLE
├── DisconnectReason          # sealed interface with record variants
├── ServerStats               # Statistics interface
├── ClientStats               # Statistics interface
└── handlers/
    ├── MessageHandler        # Functional interface for message handling
    ├── SessionHandler        # Session lifecycle callbacks
    └── ErrorHandler          # Error handling callbacks
```

### clientserver-impl

```
dk.cloudware.clientserver.impl/    # NOT exported
├── DefaultServer
├── DefaultServerFactory
├── DefaultClient
├── DefaultClientFactory
├── DefaultSession
├── transport/
│   ├── UdpTransport
│   ├── ReliabilityLayer
│   └── EncryptionLayer
└── serialization/
    ├── MessageSerializer
    └── TypeRegistry
```

### clientserver-test

```
dk.cloudware.clientserver.test/    # exported
├── MockSession
├── InMemoryServer
├── InMemoryClient
└── InMemoryTransport
```

## Key Interfaces

### Core Interfaces

**Server** — Accepts connections, manages sessions
- Start/stop server
- Register message handlers
- Broadcast to all sessions
- Access active sessions and statistics

**Client** — Connects to server, sends/receives messages
- Connect/disconnect
- Register message handlers
- Send messages to server
- Access connection statistics

**Session** — Logical connection that survives disconnects
- Send messages with delivery mode
- Close session (optionally with reason)
- Attach application state
- Query session ID and state

**Protocol** — Type-safe message contract
- Builder for defining client/server message hierarchies
- Validation of message types at build time
- Protocol hash for version matching

### Handler Interfaces

**MessageHandler<T>** — Processes incoming messages
```java
@FunctionalInterface
interface MessageHandler<T>
{
    void handle(Session session, T message);
}
```

**SessionHandler** — Session lifecycle events
- onSessionStarted, onSessionDisconnected
- onSessionReconnected, onSessionExpired

**ErrorHandler** — Error events
- onHandlerError (exception in message handler)

### Supporting Types

**Delivery** — Message delivery mode
- RELIABLE: exactly-once, ordered
- UNRELIABLE: fire-and-forget

**DisconnectReason** — Sealed interface for disconnect causes
- NetworkError, Timeout, KickedByServer
- ProtocolError, ServerShutdown

**ServerStats / ClientStats** — Observable metrics
- Connection counts, message rates
- RTT, retransmit rates

## Component Interactions

### Layer Diagram

```
┌─────────────────────────────────────────────────────────────┐
│                      Application Layer                       │
│         (Message handlers, game logic, business rules)       │
└──────────────────────────┬──────────────────────────────────┘
                           │ MessageHandler callbacks
                           │ session.send(message, delivery)
┌──────────────────────────▼──────────────────────────────────┐
│                       Session Layer                          │
│              (Session, message dispatch, state)              │
└──────────────────────────┬──────────────────────────────────┘
                           │ Serialized messages
                           │ Sequence numbers
┌──────────────────────────▼──────────────────────────────────┐
│                     Reliability Layer                        │
│           (Acknowledgments, retransmission, ordering)        │
└──────────────────────────┬──────────────────────────────────┘
                           │ Reliable/unreliable packets
┌──────────────────────────▼──────────────────────────────────┐
│                     Encryption Layer                         │
│              (ChaCha20-Poly1305, ECDH handshake)            │
└──────────────────────────┬──────────────────────────────────┘
                           │ Encrypted bytes
┌──────────────────────────▼──────────────────────────────────┐
│                     Transport Layer                          │
│                     (UDP sockets)                            │
└─────────────────────────────────────────────────────────────┘
```

### Data Flow

**Outbound (Application → Network):**
1. Application calls `session.send(message, delivery)`
2. Session layer serializes message using Protocol's TypeRegistry
3. Reliability layer assigns sequence number (if reliable), queues for ack
4. Encryption layer encrypts packet
5. Transport layer sends via UDP

**Inbound (Network → Application):**
1. Transport layer receives UDP packet
2. Encryption layer decrypts and verifies
3. Reliability layer processes sequence number, sends ack, orders messages
4. Session layer deserializes message using Protocol's TypeRegistry
5. Session dispatches to registered MessageHandler

### Handshake Flow

```
Client                                  Server
   │                                       │
   │──── ClientHello (ECDH pubkey) ───────►│
   │                                       │
   │◄─── ServerHello (ECDH pubkey, sig) ───│
   │                                       │
   │     [Both derive shared secret]       │
   │                                       │
   │──── Connect (protocol hash) ─────────►│
   │                                       │
   │◄─── Accept (session token, params) ───│
   │                                       │
   │     [Session established]             │
   │                                       │
```

All messages after ServerHello are encrypted.

## Key Abstractions

### Transport and Session Separation

The **transport layer** handles:
- Physical UDP communication
- Encryption/decryption
- Packet reliability (acks, retransmission)
- Internal protocol messages (heartbeat, handshake)

The **session layer** handles:
- Logical connection identity (session token)
- Message serialization/deserialization
- Application message dispatch
- Session state and attachments

A session can survive transport reconnection. When the physical connection drops, the session enters a disconnected state. On reconnect, the session resumes with sequence numbers synchronized.

### Serialization Integration

Serialization is built at Protocol construction time:
1. Protocol.builder() receives sealed interface classes
2. Builder scans permitted types recursively
3. For each record, generates serializer from component types
4. Assigns type IDs deterministically
5. Computes protocol hash for version matching

At runtime:
- Session layer uses TypeRegistry to serialize/deserialize
- Type ID (2 bytes) prefixes each message
- No reflection at message time (only at startup)

### Factory Pattern

Following coding guidelines, behavioral objects use factories:

```java
// API module
interface ServerFactory
{
    Server create(ServerConfig config);
}

// Impl module (not exported)
class DefaultServerFactory implements ServerFactory
{
    public Server create(ServerConfig config)
    {
        return new DefaultServer(config);
    }
}
```

Records (config, messages) use direct construction.

### Configuration

Builder pattern for Server and Client creation:

```java
Server server = Server.builder()
    .port(7777)
    .protocol(protocol)
    .privateKey(key)
    .build();
```

Builder validates required parameters, applies defaults for optional ones.
