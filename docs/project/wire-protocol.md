# Wire Protocol Specification

## Overview

This document specifies the byte-level format of packets transmitted between client and server. All multi-byte integers are big-endian unless otherwise noted.

## Packet Structure

### Unencrypted Packets (Handshake Only)

Used only during initial key exchange before encryption is established.

```
┌─────────────┬─────────────────────┐
│ Type (1)    │ Payload (variable)  │
└─────────────┴─────────────────────┘
```

| Field   | Size    | Description                    |
|---------|---------|--------------------------------|
| Type    | 1 byte  | Packet type identifier         |
| Payload | variable| Type-specific data             |

### Encrypted Packets (Post-Handshake)

All packets after handshake use ChaCha20-Poly1305 AEAD encryption.

```
┌─────────────┬──────────────────────────────────────┐
│ Nonce (12)  │ Ciphertext + AuthTag (variable + 16) │
└─────────────┴──────────────────────────────────────┘
```

| Field      | Size     | Description                              |
|------------|----------|------------------------------------------|
| Nonce      | 12 bytes | Unique per-packet, derived from counter  |
| Ciphertext | variable | Encrypted payload                        |
| AuthTag    | 16 bytes | Poly1305 authentication tag (appended)   |

The decrypted payload has the same structure as unencrypted packets:

```
┌─────────────┬─────────────────────┐
│ Type (1)    │ Payload (variable)  │
└─────────────┴─────────────────────┘
```

## Packet Types

### Unencrypted Types (0x01 - 0x0F)

| Type | Name        | Direction       | Description                |
|------|-------------|-----------------|----------------------------|
| 0x01 | ClientHello | Client → Server | Initiates key exchange     |
| 0x02 | ServerHello | Server → Client | Completes key exchange     |

### Encrypted Types (0x10 - 0xFF)

| Type | Name          | Direction       | Description                |
|------|---------------|-----------------|----------------------------|
| 0x10 | Connect       | Client → Server | Session establishment      |
| 0x11 | Accept        | Server → Client | Session confirmed          |
| 0x12 | Reject        | Server → Client | Session rejected           |
| 0x20 | Data          | Both            | Application message        |
| 0x21 | Ack           | Both            | Acknowledgment             |
| 0x30 | Heartbeat     | Both            | Connection keepalive       |
| 0x31 | HeartbeatAck  | Both            | Heartbeat response         |
| 0x40 | Disconnect    | Both            | Graceful disconnect        |

## Handshake Flow

```
Client                                     Server
   │                                          │
   │─────── ClientHello (unencrypted) ───────►│
   │                                          │
   │◄────── ServerHello (unencrypted) ────────│
   │                                          │
   │        [Both derive shared secret]       │
   │                                          │
   │═══════ Connect (encrypted) ═════════════►│
   │                                          │
   │◄══════ Accept/Reject (encrypted) ════════│
   │                                          │
   │        [Session established]             │
   │                                          │
```

## Packet Definitions

### ClientHello (0x01)

```
┌─────────────┬─────────────┬─────────────────────┐
│ Type (1)    │ Version (1) │ ClientPublicKey (32)│
└─────────────┴─────────────┴─────────────────────┘
```

| Field           | Size     | Description                         |
|-----------------|----------|-------------------------------------|
| Type            | 1 byte   | 0x01                                |
| Version         | 1 byte   | Protocol version (currently 0x01)  |
| ClientPublicKey | 32 bytes | Client's ephemeral X25519 public key|

### ServerHello (0x02)

```
┌─────────────┬─────────────┬─────────────────────┬───────────────┐
│ Type (1)    │ Version (1) │ ServerPublicKey (32)│ Signature (64)│
└─────────────┴─────────────┴─────────────────────┴───────────────┘
```

| Field           | Size     | Description                              |
|-----------------|----------|------------------------------------------|
| Type            | 1 byte   | 0x02                                     |
| Version         | 1 byte   | Protocol version                         |
| ServerPublicKey | 32 bytes | Server's ephemeral X25519 public key     |
| Signature       | 64 bytes | Ed25519 signature over ServerPublicKey   |

The signature is created using the server's long-term Ed25519 private key. Client verifies using the server's public key (distributed with client application).

### Key Derivation

After exchanging public keys:

1. Both sides compute shared secret: `X25519(myPrivate, theirPublic)`
2. Derive encryption key: `HKDF-SHA256(shared_secret, salt="clientserver-v1", info="encryption", length=32)`
3. Derive nonce base: `HKDF-SHA256(shared_secret, salt="clientserver-v1", info="nonce", length=12)`

Nonce for each packet: `nonce_base XOR packet_counter` (8-byte counter, zero-padded to 12 bytes)

### Connect (0x10)

Sent by client after encryption is established.

```
┌─────────────┬──────────────────┬────────────────┬───────────────────────┐
│ Type (1)    │ ProtocolHash (32)│ TokenLength (1)│ SessionToken (0 or 16)│
└─────────────┴──────────────────┴────────────────┴───────────────────────┘
     (if reconnecting, continues with:)
┌─────────────────────┐
│ LastReceivedSeq (4) │
└─────────────────────┘
```

| Field           | Size     | Description                              |
|-----------------|----------|------------------------------------------|
| Type            | 1 byte   | 0x10                                     |
| ProtocolHash    | 32 bytes | SHA-256 hash of protocol structure       |
| TokenLength     | 1 byte   | 0 for new connection, 16 for reconnect   |
| SessionToken    | 0 or 16  | Token from previous session (if reconnect)|
| LastReceivedSeq | 4 bytes  | Last sequence received (only if reconnect)|

### Accept (0x11)

Sent by server to confirm session establishment.

```
┌─────────────┬──────────────────┬───────────────────┬──────────────────┬─────────────────────┐
│ Type (1)    │ SessionToken (16)│ HeartbeatMs (4)   │ SessionTimeoutMs │ LastReceivedSeq (4) │
│             │                  │                   │ (4)              │                     │
└─────────────┴──────────────────┴───────────────────┴──────────────────┴─────────────────────┘
```

| Field            | Size     | Description                              |
|------------------|----------|------------------------------------------|
| Type             | 1 byte   | 0x11                                     |
| SessionToken     | 16 bytes | Session identifier (new or confirmed)    |
| HeartbeatMs      | 4 bytes  | Heartbeat interval in milliseconds       |
| SessionTimeoutMs | 4 bytes  | Session timeout in milliseconds          |
| LastReceivedSeq  | 4 bytes  | Server's last received sequence (for sync)|

### Reject (0x12)

Sent by server when session cannot be established.

```
┌─────────────┬──────────────┬───────────────────┬─────────────────────┐
│ Type (1)    │ ReasonCode(1)│ MessageLength (2) │ Message (variable)  │
└─────────────┴──────────────┴───────────────────┴─────────────────────┘
```

| Field         | Size     | Description                              |
|---------------|----------|------------------------------------------|
| Type          | 1 byte   | 0x12                                     |
| ReasonCode    | 1 byte   | Rejection reason (see below)             |
| MessageLength | 2 bytes  | Length of message string                 |
| Message       | variable | UTF-8 encoded reason message             |

**Reason Codes:**

| Code | Meaning           |
|------|-------------------|
| 0x01 | Protocol mismatch |
| 0x02 | Server full       |
| 0x03 | Session expired   |
| 0x04 | Invalid token     |
| 0x05 | Authentication failed |

### Data (0x20)

Carries application messages.

```
┌─────────────┬───────────┬─────────────────┬──────────────┬────────────────┬─────────────────┐
│ Type (1)    │ Flags (1) │ Sequence (0/4)  │ AckSeq (0/4) │ MsgTypeId (2)  │ Payload (var)   │
└─────────────┴───────────┴─────────────────┴──────────────┴────────────────┴─────────────────┘
```

| Field     | Size        | Description                              |
|-----------|-------------|------------------------------------------|
| Type      | 1 byte      | 0x20                                     |
| Flags     | 1 byte      | Bit flags (see below)                    |
| Sequence  | 0 or 4 bytes| Sequence number (if RELIABLE flag set)   |
| AckSeq    | 0 or 4 bytes| Piggybacked ack (if HAS_ACK flag set)    |
| MsgTypeId | 2 bytes     | Application message type identifier      |
| Payload   | variable    | Serialized message data                  |

**Flags:**

| Bit | Name     | Description                              |
|-----|----------|------------------------------------------|
| 0   | RELIABLE | Message has sequence number, needs ack   |
| 1   | HAS_ACK  | Packet includes piggybacked ack          |
| 2-7 | Reserved | Must be zero                             |

### Ack (0x21)

Standalone acknowledgment (when no data to piggyback on).

```
┌─────────────┬─────────────────┬───────────────────┐
│ Type (1)    │ AckSequence (4) │ AckBitmap (4)     │
└─────────────┴─────────────────┴───────────────────┘
```

| Field       | Size    | Description                                |
|-------------|---------|--------------------------------------------|
| Type        | 1 byte  | 0x21                                       |
| AckSequence | 4 bytes | Highest consecutive sequence received      |
| AckBitmap   | 4 bytes | Bitmap for 32 sequences after AckSequence  |

The bitmap allows selective acknowledgment: bit N indicates sequence `AckSequence + 1 + N` was received. This helps with out-of-order delivery detection.

### Heartbeat (0x30)

```
┌─────────────┬───────────────┐
│ Type (1)    │ Timestamp (8) │
└─────────────┴───────────────┘
```

| Field     | Size    | Description                              |
|-----------|---------|------------------------------------------|
| Type      | 1 byte  | 0x30                                     |
| Timestamp | 8 bytes | Sender's timestamp (milliseconds)        |

### HeartbeatAck (0x31)

```
┌─────────────┬───────────────┬───────────────┐
│ Type (1)    │ EchoTime (8)  │ Timestamp (8) │
└─────────────┴───────────────┴───────────────┘
```

| Field     | Size    | Description                              |
|-----------|---------|------------------------------------------|
| Type      | 1 byte  | 0x31                                     |
| EchoTime  | 8 bytes | Timestamp from received Heartbeat        |
| Timestamp | 8 bytes | Responder's current timestamp            |

RTT can be calculated as: `current_time - EchoTime`

### Disconnect (0x40)

```
┌─────────────┬──────────────┬───────────────────┬─────────────────────┐
│ Type (1)    │ ReasonCode(1)│ MessageLength (2) │ Message (variable)  │
└─────────────┴──────────────┴───────────────────┴─────────────────────┘
```

| Field         | Size     | Description                              |
|---------------|----------|------------------------------------------|
| Type          | 1 byte   | 0x40                                     |
| ReasonCode    | 1 byte   | Disconnect reason (see below)            |
| MessageLength | 2 bytes  | Length of message string                 |
| Message       | variable | UTF-8 encoded reason message             |

**Reason Codes:**

| Code | Meaning           |
|------|-------------------|
| 0x00 | Normal close      |
| 0x01 | Kicked by peer    |
| 0x02 | Protocol error    |
| 0x03 | Shutdown          |

## Message Serialization

Application messages are serialized to the Payload field of Data packets.

### Type ID Assignment

Type IDs are assigned deterministically at Protocol build time:

1. Enumerate all permitted types in each sealed hierarchy (sorted by fully-qualified name)
2. Client message types: IDs 0x0000 - 0x7FFF
3. Server message types: IDs 0x8000 - 0xFFFF

### Field Encoding

Fields are serialized in record component order with no field names or separators.

| Java Type        | Wire Format                                    |
|------------------|------------------------------------------------|
| `byte`           | 1 byte                                         |
| `short`          | 2 bytes, big-endian                            |
| `int`            | 4 bytes, big-endian                            |
| `long`           | 8 bytes, big-endian                            |
| `float`          | 4 bytes, IEEE 754                              |
| `double`         | 8 bytes, IEEE 754                              |
| `boolean`        | 1 byte (0x00 = false, 0x01 = true)             |
| `char`           | 2 bytes, big-endian                            |
| `String`         | 2-byte length (unsigned) + UTF-8 bytes         |
| `byte[]`         | 4-byte length + raw bytes                      |
| `List<T>`        | 2-byte count (unsigned) + serialized elements  |
| `Optional<T>`    | 1-byte presence (0/1) + value if present       |
| `enum`           | 2 bytes, ordinal value                         |
| nested record    | Serialized fields concatenated (no wrapper)    |

### String Length Limits

- Maximum string length: 65,535 bytes (2-byte length field)
- Maximum list size: 65,535 elements
- Maximum byte array: 2,147,483,647 bytes (4-byte length field)

### Example

Given:
```java
record PlayerMove(int x, int y, boolean running) implements ClientMessage {}
```

A `PlayerMove(100, 200, true)` serializes to:
```
00 00 00 64    // x = 100
00 00 00 C8    // y = 200
01             // running = true
```

With message type ID 0x0003, the full Data packet payload would be:
```
00 03          // MsgTypeId
00 00 00 64    // x
00 00 00 C8    // y
01             // running
```

## Protocol Hash Computation

The protocol hash ensures client and server have identical message definitions.

1. For each sealed hierarchy (client messages, then server messages):
   - For each permitted type (sorted by fully-qualified name):
     - Append type name (UTF-8)
     - For each component (in declaration order):
       - Append component name (UTF-8)
       - Append component type descriptor
2. Compute SHA-256 of the concatenated data

Type descriptors:
- Primitives: `B` (byte), `S` (short), `I` (int), `J` (long), `F` (float), `D` (double), `Z` (boolean), `C` (char)
- String: `Ljava/lang/String;`
- List: `Ljava/util/List<` + element descriptor + `>;`
- Optional: `Ljava/util/Optional<` + element descriptor + `>;`
- Record: `L` + fully-qualified name + `;`
- Enum: `L` + fully-qualified name + `;`

## Reliability Layer

### Sequence Numbers

- 4-byte unsigned integers (0 to 4,294,967,295)
- Wrap around to 0 after max value
- Each direction maintains independent sequence counters
- Initial sequence is 0

### Retransmission

Unacknowledged reliable messages are retransmitted based on:

1. **Initial timeout**: `RTT * 1.5` (minimum 50ms)
2. **Backoff**: Exponential, up to maximum of 2 seconds
3. **Max attempts**: Configurable, default 10

### Ordering

Reliable messages are delivered to the application in sequence order:
- Hold out-of-order messages in a buffer
- Deliver when gap is filled
- Buffer size is bounded (configurable)

## Nonce Management

To prevent nonce reuse:

1. Each side maintains a 64-bit packet counter (starts at 0)
2. Counter increments for each packet sent
3. Nonce = `nonce_base XOR (counter as 12 bytes, little-endian padded)`
4. On reconnection, counters continue from last known values (exchanged in Connect/Accept)

If counter would overflow (after 2^64 packets), connection must be terminated and re-established.
