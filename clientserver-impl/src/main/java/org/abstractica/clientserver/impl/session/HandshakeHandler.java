package org.abstractica.clientserver.impl.session;

import org.abstractica.clientserver.impl.crypto.KeyExchange;
import org.abstractica.clientserver.impl.crypto.PacketEncryptor;
import org.abstractica.clientserver.impl.crypto.Signer;
import org.abstractica.clientserver.impl.protocol.Accept;
import org.abstractica.clientserver.impl.protocol.ClientHello;
import org.abstractica.clientserver.impl.protocol.Connect;
import org.abstractica.clientserver.impl.protocol.PacketCodec;
import org.abstractica.clientserver.impl.protocol.Reject;
import org.abstractica.clientserver.impl.protocol.ServerHello;
import org.abstractica.clientserver.impl.serialization.DefaultProtocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.security.PrivateKey;
import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Handles the connection handshake protocol.
 *
 * <p>Processes ClientHello/ServerHello exchange and Connect/Accept flow.</p>
 */
public class HandshakeHandler
{
    private static final Logger LOG = LoggerFactory.getLogger(HandshakeHandler.class);
    private static final int PROTOCOL_VERSION = 1;

    private final SessionManager sessionManager;
    private final PrivateKey serverPrivateSigningKey;
    private final DefaultProtocol protocol;
    private final Duration heartbeatInterval;
    private final Duration sessionTimeout;
    private final BiConsumer<ByteBuffer, SocketAddress> packetSender;
    private final SessionCallback sessionCallback;
    private final Consumer<DefaultSession> onSessionCreated;

    /**
     * Creates a new handshake handler.
     *
     * @param sessionManager          the session manager
     * @param serverPrivateSigningKey the server's Ed25519 private key
     * @param protocol                the message protocol
     * @param heartbeatInterval       heartbeat interval
     * @param sessionTimeout          session timeout
     * @param packetSender            function to send packets
     * @param sessionCallback         callback for created sessions
     * @param onSessionCreated        callback when a new session is created
     */
    public HandshakeHandler(
            SessionManager sessionManager,
            PrivateKey serverPrivateSigningKey,
            DefaultProtocol protocol,
            Duration heartbeatInterval,
            Duration sessionTimeout,
            BiConsumer<ByteBuffer, SocketAddress> packetSender,
            SessionCallback sessionCallback,
            Consumer<DefaultSession> onSessionCreated
    )
    {
        this.sessionManager = Objects.requireNonNull(sessionManager, "sessionManager");
        this.serverPrivateSigningKey = Objects.requireNonNull(serverPrivateSigningKey, "serverPrivateSigningKey");
        this.protocol = Objects.requireNonNull(protocol, "protocol");
        this.heartbeatInterval = Objects.requireNonNull(heartbeatInterval, "heartbeatInterval");
        this.sessionTimeout = Objects.requireNonNull(sessionTimeout, "sessionTimeout");
        this.packetSender = Objects.requireNonNull(packetSender, "packetSender");
        this.sessionCallback = Objects.requireNonNull(sessionCallback, "sessionCallback");
        this.onSessionCreated = Objects.requireNonNull(onSessionCreated, "onSessionCreated");
    }

    /**
     * Handles a ClientHello packet.
     *
     * @param clientHello the received packet
     * @param from        the sender's address
     */
    public void handleClientHello(ClientHello clientHello, SocketAddress from)
    {
        LOG.debug("ClientHello from {}: version={}", from, clientHello.version());

        // Check if there's already a pending handshake for this address
        PendingHandshake existing = sessionManager.findPendingHandshake(from);
        if (existing != null)
        {
            // Client may have retried - remove old pending handshake
            sessionManager.removePendingHandshake(from);
        }

        // Generate server ephemeral key pair
        KeyExchange keyExchange = new KeyExchange();
        byte[] serverPublicKey = keyExchange.getPublicKey();

        // Sign the server's ephemeral public key
        byte[] signature = Signer.sign(serverPublicKey, serverPrivateSigningKey);

        // Compute shared secret and derive keys
        byte[] sharedSecret = keyExchange.computeSharedSecret(clientHello.clientPublicKey());
        KeyExchange.DerivedKeys derivedKeys = KeyExchange.deriveKeys(sharedSecret);

        // Create encryptor for subsequent encrypted packets
        PacketEncryptor encryptor = new PacketEncryptor(derivedKeys);

        // Store pending handshake
        PendingHandshake pending = new PendingHandshake(
                from,
                keyExchange,
                encryptor,
                System.currentTimeMillis()
        );
        sessionManager.registerPendingHandshake(pending);

        // Send ServerHello (unencrypted)
        ServerHello serverHello = new ServerHello(PROTOCOL_VERSION, serverPublicKey, signature);
        ByteBuffer encoded = PacketCodec.encode(serverHello);
        packetSender.accept(encoded, from);

        LOG.debug("ServerHello sent to {}", from);
    }

    /**
     * Handles a Connect packet.
     *
     * @param connect   the received packet
     * @param from      the sender's address
     * @param encryptor the encryptor from the pending handshake
     */
    public void handleConnect(Connect connect, SocketAddress from, PacketEncryptor encryptor)
    {
        LOG.debug("Connect from {}: reconnect={}", from, connect.sessionToken().isPresent());

        // Remove the pending handshake
        sessionManager.removePendingHandshake(from);

        // Validate protocol hash
        byte[] expectedHash = protocol.getHashBytes();
        if (!Arrays.equals(connect.protocolHash(), expectedHash))
        {
            LOG.warn("Protocol mismatch from {}", from);
            sendReject(from, encryptor, Reject.RejectReason.PROTOCOL_MISMATCH,
                    "Protocol hash mismatch");
            return;
        }

        // Handle reconnection
        if (connect.sessionToken().isPresent())
        {
            handleReconnect(connect, from, encryptor);
            return;
        }

        // New connection
        handleNewConnection(from, encryptor);
    }

    private void handleNewConnection(SocketAddress from, PacketEncryptor encryptor)
    {
        // Check max connections
        if (!sessionManager.canAcceptConnection())
        {
            LOG.warn("Server full, rejecting connection from {}", from);
            sendReject(from, encryptor, Reject.RejectReason.SERVER_FULL,
                    "Server is full");
            return;
        }

        // Generate session token
        byte[] sessionToken = sessionManager.generateSessionToken();

        // Create session
        DefaultSession session = new DefaultSession(
                sessionToken,
                from,
                encryptor,
                sessionCallback,
                heartbeatInterval,
                sessionTimeout
        );

        // Register and start session
        sessionManager.registerSession(session);
        session.start();

        // Send Accept
        Accept accept = new Accept(
                sessionToken,
                (int) heartbeatInterval.toMillis(),
                (int) sessionTimeout.toMillis(),
                0 // lastReceivedSeq - new connection starts at 0
        );

        ByteBuffer encoded = PacketCodec.encode(accept);
        ByteBuffer encrypted = encryptor.encrypt(encoded);
        packetSender.accept(encrypted, from);

        LOG.info("Session created: id={}, address={}", session.getId(), from);

        // Notify callback
        onSessionCreated.accept(session);
    }

    private void handleReconnect(Connect connect, SocketAddress from, PacketEncryptor encryptor)
    {
        byte[] sessionToken = connect.sessionToken().orElseThrow();
        DefaultSession session = sessionManager.findByToken(sessionToken);

        if (session == null)
        {
            LOG.warn("Reconnect with unknown token from {}", from);
            sendReject(from, encryptor, Reject.RejectReason.INVALID_TOKEN,
                    "Session not found");
            return;
        }

        if (session.getState() != SessionState.DISCONNECTED)
        {
            // Session is still connected - could be duplicate or attack
            LOG.warn("Reconnect to connected session from {}, session address {}",
                    from, session.getRemoteAddress());
            sendReject(from, encryptor, Reject.RejectReason.INVALID_TOKEN,
                    "Session is still connected");
            return;
        }

        // Update session with new address and encryptor
        SocketAddress oldAddress = session.getRemoteAddress();
        sessionManager.updateSessionAddress(session, oldAddress, from);

        // Transition to connected
        session.transitionToConnected();

        // Get last received sequence for sync
        int lastReceivedSeq = session.getReliabilityLayer().getPendingOutboundCount() > 0
                ? 0 // Will trigger retransmits
                : 0;

        // Send Accept
        Accept accept = new Accept(
                sessionToken,
                (int) heartbeatInterval.toMillis(),
                (int) sessionTimeout.toMillis(),
                lastReceivedSeq
        );

        ByteBuffer encoded = PacketCodec.encode(accept);
        ByteBuffer encrypted = encryptor.encrypt(encoded);
        packetSender.accept(encrypted, from);

        LOG.info("Session reconnected: id={}, old={}, new={}", session.getId(), oldAddress, from);
    }

    private void sendReject(SocketAddress to, PacketEncryptor encryptor,
                            Reject.RejectReason reason, String message)
    {
        Reject reject = new Reject(reason, message);
        ByteBuffer encoded = PacketCodec.encode(reject);
        ByteBuffer encrypted = encryptor.encrypt(encoded);
        packetSender.accept(encrypted, to);
    }
}
