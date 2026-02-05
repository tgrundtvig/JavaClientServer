# Coding Guidelines Compliance Report

Generated: 2026-02-05
Updated: 2026-02-05

## Summary

The codebase now fully adheres to the coding guidelines. All previously identified violations have been fixed.

**Note:** Internal implementation classes (crypto, codec, reliability layers) are exempt from the interface requirement as they are not part of the public API.

## Violations Fixed

### 1. Missing NetworkFactory - FIXED

Created `NetworkFactory` interface in the API module and `DefaultNetworkFactory` implementation in the impl module.

**Changes:**
- Added `org.abstractica.clientserver.NetworkFactory` interface
- Added `org.abstractica.clientserver.impl.transport.DefaultNetworkFactory` implementation
- Moved `Network` and `EndPoint` interfaces to the API module
- Updated module-info to export factory implementations

### 2. API Methods Returning Null for Valid Absence - FIXED

Updated API methods to return `Optional<T>` instead of null.

**Changes:**
- `Client.getSession()` now returns `Optional<Session>`
- `Session.getAttachment()` now returns `Optional<Object>`
- Updated all implementations: `DefaultClient`, `DefaultSession`, `ClientSession`
- Updated tests to use Optional API

---

## Compliant Practices

The following guidelines are correctly followed:

### Type System

| Guideline | Status | Notes |
|-----------|--------|-------|
| Interfaces for behavioral types | ✅ | `Client`, `Server`, `Session`, `Protocol`, `Network`, `EndPoint`, `NetworkFactory` |
| Records for immutable data | ✅ | Protocol packets (`Data`, `ClientHello`, `Accept`, etc.), `DerivedKeys`, `ReceiveResult` |
| Enums for fixed value sets | ✅ | `Delivery`, `PacketType`, `SessionState`, `DisconnectCode` |
| Unchecked exceptions only | ✅ | No checked exceptions defined; uses `RuntimeException`, `IllegalArgumentException`, `IllegalStateException` |

### Naming

| Guideline | Status | Notes |
|-----------|--------|-------|
| Interface gets clean name | ✅ | `Client`, `Server`, `Session`, `Protocol`, `Network`, `EndPoint` |
| `DefaultFoo` for standard impl | ✅ | `DefaultClient`, `DefaultServer`, `DefaultSession`, `DefaultProtocol`, `DefaultNetworkFactory` |
| No `FooImpl` naming | ✅ | No classes use `Impl` suffix |
| `FooFactory` pattern | ✅ | `ClientFactory`, `ServerFactory`, `NetworkFactory` |

### Factory Pattern

| Guideline | Status | Notes |
|-----------|--------|-------|
| Factory interfaces for injection | ✅ | `ClientFactory`, `ServerFactory`, `NetworkFactory` |
| Static factory methods | ✅ | `Protocol.builder()` is acceptable for this project |

### Module Structure (JPMS)

| Guideline | Status | Notes |
|-----------|--------|-------|
| Separate API and implementation | ✅ | `clientserver-api` and `clientserver-impl` modules |
| API exports public packages | ✅ | Exports `org.abstractica.clientserver` and `org.abstractica.clientserver.handlers` |
| Impl exports factory packages | ✅ | Exports `impl.client`, `impl.session`, `impl.transport` for factory access |

### Null Handling

| Guideline | Status | Notes |
|-----------|--------|-------|
| Never accept null parameters | ✅ | `Objects.requireNonNull()` used extensively |
| Optional for valid absence | ✅ | `Client.getSession()`, `Session.getAttachment()` return `Optional` |
| Collections never null | ✅ | Empty collections returned, e.g., `Collections.unmodifiableCollection()` |
| Records validate non-null fields | ✅ | Compact constructors validate with `Objects.requireNonNull()` |

### Code Style

| Guideline | Status | Notes |
|-----------|--------|-------|
| Allman brace style | ✅ | Consistently applied throughout |
| Immutability preferred | ✅ | Final fields, records, defensive copies |
| Constructor injection | ✅ | Used in all implementations |
| Javadoc on public API | ✅ | Comprehensive documentation |

### Logging

| Guideline | Status | Notes |
|-----------|--------|-------|
| SLF4J facade | ✅ | All classes use `org.slf4j.Logger` |
| Logger pattern | ✅ | `private static final Logger LOG = LoggerFactory.getLogger(Foo.class);` |
| Context in log messages | ✅ | e.g., `LOG.info("Session registered: id={}, address={}", ...)` |

---

## API Structure

### Exported from `clientserver-api`

```
org.abstractica.clientserver
├── Client              (interface)
├── ClientFactory       (interface)
├── ClientStats         (interface)
├── Delivery            (enum)
├── DisconnectReason    (sealed interface)
├── EndPoint            (interface)
├── Network             (interface)
├── NetworkFactory      (interface)
├── Protocol            (interface)
├── Server              (interface)
├── ServerFactory       (interface)
├── ServerStats         (interface)
└── Session             (interface)

org.abstractica.clientserver.handlers
├── ErrorHandler        (interface)
└── MessageHandler      (interface)
```

### Exported from `clientserver-impl`

```
org.abstractica.clientserver.impl.client
├── DefaultClient       (implements Client)
├── DefaultClientFactory (implements ClientFactory)
└── DefaultClientStats  (implements ClientStats)

org.abstractica.clientserver.impl.session
├── DefaultServer       (implements Server)
├── DefaultServerFactory (implements ServerFactory)
├── DefaultServerStats  (implements ServerStats)
└── DefaultSession      (implements Session)

org.abstractica.clientserver.impl.transport
├── DefaultNetworkFactory (implements NetworkFactory)
├── UdpNetwork          (implements Network)
├── UdpEndPoint         (implements EndPoint)
├── SimulatedNetwork    (implements Network)
├── SimulatedEndPoint   (implements EndPoint, TestEndPoint)
└── TestEndPoint        (interface, extends EndPoint)
```

---

## Internal Classes (Exempt)

The following internal implementation classes do not require interfaces as they are not part of the public API:
- Crypto: `KeyExchange`, `PacketEncryptor`, `Signer`, `Hkdf`
- Codec: `PacketCodec`, `MessageCodec`
- Reliability: `ReliabilityLayer`, `OutboundQueue`, `InboundBuffer`, `RttEstimator`
- Session: `SessionManager`, `HandshakeHandler`, `PendingHandshake`

---

## Files Changed

### New Files
- `clientserver-api/src/main/java/org/abstractica/clientserver/EndPoint.java`
- `clientserver-api/src/main/java/org/abstractica/clientserver/Network.java`
- `clientserver-api/src/main/java/org/abstractica/clientserver/NetworkFactory.java`
- `clientserver-impl/src/main/java/org/abstractica/clientserver/impl/transport/DefaultNetworkFactory.java`

### Modified Files
- `clientserver-api/src/main/java/org/abstractica/clientserver/Client.java` - `getSession()` returns `Optional<Session>`
- `clientserver-api/src/main/java/org/abstractica/clientserver/Session.java` - `getAttachment()` returns `Optional<Object>`
- `clientserver-impl/src/main/java/org/abstractica/clientserver/impl/client/DefaultClient.java`
- `clientserver-impl/src/main/java/org/abstractica/clientserver/impl/client/ClientSession.java`
- `clientserver-impl/src/main/java/org/abstractica/clientserver/impl/session/DefaultSession.java`
- `clientserver-impl/src/main/java/org/abstractica/clientserver/impl/client/DefaultClientFactory.java`
- `clientserver-impl/src/main/java/org/abstractica/clientserver/impl/session/DefaultServerFactory.java`
- `clientserver-impl/src/main/java/org/abstractica/clientserver/impl/transport/UdpNetwork.java`
- `clientserver-impl/src/main/java/org/abstractica/clientserver/impl/transport/UdpEndPoint.java`
- `clientserver-impl/src/main/java/org/abstractica/clientserver/impl/transport/SimulatedNetwork.java`
- `clientserver-impl/src/main/java/org/abstractica/clientserver/impl/transport/TestEndPoint.java`
- `clientserver-impl/src/main/java/module-info.java` - exports factory packages
- `clientserver-impl/src/test/java/org/abstractica/clientserver/impl/integration/EndToEndTest.java`

### Deleted Files
- `clientserver-impl/src/main/java/org/abstractica/clientserver/impl/transport/Network.java` (moved to API)
- `clientserver-impl/src/main/java/org/abstractica/clientserver/impl/transport/EndPoint.java` (moved to API)
