package org.abstractica.clientserver.impl.session;

import org.abstractica.clientserver.Delivery;
import org.abstractica.clientserver.DisconnectReason;
import org.abstractica.clientserver.Server;
import org.abstractica.clientserver.ServerStats;
import org.abstractica.clientserver.Session;
import org.abstractica.clientserver.handlers.ErrorHandler;
import org.abstractica.clientserver.handlers.MessageHandler;
import org.abstractica.clientserver.impl.crypto.PacketEncryptor;
import org.abstractica.clientserver.impl.protocol.Accept;
import org.abstractica.clientserver.impl.protocol.Ack;
import org.abstractica.clientserver.impl.protocol.ClientHello;
import org.abstractica.clientserver.impl.protocol.Connect;
import org.abstractica.clientserver.impl.protocol.Data;
import org.abstractica.clientserver.impl.protocol.Disconnect;
import org.abstractica.clientserver.impl.protocol.Heartbeat;
import org.abstractica.clientserver.impl.protocol.HeartbeatAck;
import org.abstractica.clientserver.impl.protocol.PacketCodec;
import org.abstractica.clientserver.impl.protocol.PacketType;
import org.abstractica.clientserver.impl.reliability.ReliabilityLayer;
import org.abstractica.clientserver.impl.serialization.DefaultProtocol;
import org.abstractica.clientserver.EndPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.security.PrivateKey;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Default implementation of the Server interface.
 *
 * <p>Manages UDP transport, sessions, handshakes, and message dispatch.</p>
 */
public class DefaultServer implements Server, SessionCallback
{
    private static final Logger LOG = LoggerFactory.getLogger(DefaultServer.class);

    private final EndPoint endPoint;
    private final SessionManager sessionManager;
    private final HandshakeHandler handshakeHandler;
    private final DefaultProtocol protocol;
    private final Duration heartbeatInterval;
    private final Duration sessionTimeout;

    private final Map<Class<?>, MessageHandler<?>> messageHandlers;
    private final List<Consumer<Session>> sessionStartedCallbacks;
    private final List<BiConsumer<Session, DisconnectReason>> sessionDisconnectedCallbacks;
    private final List<Consumer<Session>> sessionReconnectedCallbacks;
    private final List<Consumer<Session>> sessionExpiredCallbacks;
    private ErrorHandler errorHandler;

    private final DefaultServerStats stats;

    private Thread tickThread;
    private volatile boolean running;
    private volatile boolean acceptingConnections;

    /**
     * Creates a new server.
     *
     * <p>Use {@link DefaultServerFactory} to create instances.</p>
     */
    DefaultServer(
            EndPoint endPoint,
            DefaultProtocol protocol,
            PrivateKey serverPrivateKey,
            Duration heartbeatInterval,
            Duration sessionTimeout,
            int maxConnections
    )
    {
        this.endPoint = Objects.requireNonNull(endPoint, "endPoint");
        this.protocol = Objects.requireNonNull(protocol, "protocol");
        this.heartbeatInterval = Objects.requireNonNull(heartbeatInterval, "heartbeatInterval");
        this.sessionTimeout = Objects.requireNonNull(sessionTimeout, "sessionTimeout");

        this.sessionManager = new SessionManager(sessionTimeout, maxConnections);
        this.stats = new DefaultServerStats(sessionManager);

        this.messageHandlers = new ConcurrentHashMap<>();
        this.sessionStartedCallbacks = new ArrayList<>();
        this.sessionDisconnectedCallbacks = new ArrayList<>();
        this.sessionReconnectedCallbacks = new ArrayList<>();
        this.sessionExpiredCallbacks = new ArrayList<>();

        this.handshakeHandler = new HandshakeHandler(
                sessionManager,
                serverPrivateKey,
                protocol,
                heartbeatInterval,
                sessionTimeout,
                this::sendPacket,
                this,
                this::notifySessionStarted
        );

        this.running = false;
        this.acceptingConnections = false;
    }

    // ========== Server Interface ==========

    @Override
    public void start()
    {
        if (running)
        {
            throw new IllegalStateException("Server already started");
        }

        LOG.info("Starting server");

        // Set up transport receive handler
        endPoint.setReceiveHandler(this::onReceive);
        endPoint.start();

        running = true;
        acceptingConnections = true;

        // Start tick thread
        tickThread = Thread.ofVirtual()
                .name("server-tick")
                .start(this::tickLoop);

        LOG.info("Server started on {}", endPoint.getLocalAddress());
    }

    @Override
    public void stop()
    {
        LOG.info("Stopping server (no new connections)");
        acceptingConnections = false;
    }

    @Override
    public void close()
    {
        if (!running)
        {
            return;
        }

        LOG.info("Closing server");

        running = false;
        acceptingConnections = false;

        // Stop tick thread
        if (tickThread != null)
        {
            tickThread.interrupt();
        }

        // Close all sessions
        for (DefaultSession session : sessionManager.getAllSessions())
        {
            session.close("Server shutdown");
            sessionManager.removeSession(session);
        }

        // Close transport
        endPoint.close();

        LOG.info("Server closed");
    }

    @Override
    public <T> void onMessage(Class<T> type, MessageHandler<T> handler)
    {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(handler, "handler");
        messageHandlers.put(type, handler);
    }

    @Override
    public void onSessionStarted(Consumer<Session> handler)
    {
        Objects.requireNonNull(handler, "handler");
        sessionStartedCallbacks.add(handler);
    }

    @Override
    public void onSessionDisconnected(BiConsumer<Session, DisconnectReason> handler)
    {
        Objects.requireNonNull(handler, "handler");
        sessionDisconnectedCallbacks.add(handler);
    }

    @Override
    public void onSessionReconnected(Consumer<Session> handler)
    {
        Objects.requireNonNull(handler, "handler");
        sessionReconnectedCallbacks.add(handler);
    }

    @Override
    public void onSessionExpired(Consumer<Session> handler)
    {
        Objects.requireNonNull(handler, "handler");
        sessionExpiredCallbacks.add(handler);
    }

    @Override
    public void onError(ErrorHandler handler)
    {
        this.errorHandler = handler;
    }

    @Override
    public void broadcast(Object message)
    {
        broadcast(message, Delivery.RELIABLE);
    }

    @Override
    public void broadcast(Object message, Delivery delivery)
    {
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(delivery, "delivery");

        for (DefaultSession session : sessionManager.getAllSessions())
        {
            if (session.getState() == SessionState.CONNECTED)
            {
                session.trySend(message, delivery);
            }
        }
    }

    @Override
    public Collection<Session> getSessions()
    {
        return Collections.unmodifiableCollection(sessionManager.getAllSessions());
    }

    @Override
    public ServerStats getStats()
    {
        return stats;
    }

    // ========== SessionCallback Interface ==========

    @Override
    public void sendPacket(ByteBuffer packet, SocketAddress destination)
    {
        endPoint.send(packet, destination);
        stats.recordBytes(packet.remaining());
    }

    @Override
    public void onSessionDisconnected(DefaultSession session, DisconnectReason reason)
    {
        for (BiConsumer<Session, DisconnectReason> callback : sessionDisconnectedCallbacks)
        {
            try
            {
                callback.accept(session, reason);
            }
            catch (Exception e)
            {
                LOG.error("Session disconnected callback error", e);
            }
        }
    }

    @Override
    public void onSessionExpired(DefaultSession session)
    {
        sessionManager.removeSession(session);

        for (Consumer<Session> callback : sessionExpiredCallbacks)
        {
            try
            {
                callback.accept(session);
            }
            catch (Exception e)
            {
                LOG.error("Session expired callback error", e);
            }
        }
    }

    @Override
    public MessageHandler<?> getHandler(Class<?> messageType)
    {
        return messageHandlers.get(messageType);
    }

    @Override
    public ErrorHandler getErrorHandler()
    {
        return errorHandler;
    }

    @Override
    public DefaultProtocol getProtocol()
    {
        return protocol;
    }

    // ========== Receive Handler ==========

    private void onReceive(ByteBuffer data, SocketAddress from)
    {
        try
        {
            processPacket(data, from);
        }
        catch (Exception e)
        {
            LOG.warn("Error processing packet from {}: {}", from, e.getMessage());
        }
    }

    private static final int CLIENT_HELLO_SIZE = 34;  // 1 type + 1 version + 32 public key

    private void processPacket(ByteBuffer data, SocketAddress from)
    {
        if (data.remaining() < 1)
        {
            return;
        }

        int typePrefix = data.get(data.position()) & 0xFF;

        switch (typePrefix)
        {
            case 0x01 -> handleClientHelloPacket(data, from);
            case 0x02 -> LOG.debug("Ignoring unexpected ServerHello from {}", from);
            case 0x03 -> handleEncryptedPacket(data, from);
            default -> LOG.debug("Ignoring packet with unknown type prefix 0x{} from {}",
                    Integer.toHexString(typePrefix), from);
        }
    }

    /**
     * Handles CLIENT_HELLO (0x01) packets.
     */
    private void handleClientHelloPacket(ByteBuffer data, SocketAddress from)
    {
        if (data.remaining() != CLIENT_HELLO_SIZE)
        {
            LOG.debug("Ignoring malformed ClientHello from {} (size {})", from, data.remaining());
            return;
        }

        if (!acceptingConnections)
        {
            LOG.debug("Ignoring ClientHello while not accepting connections");
            return;
        }

        // Check for pending handshake (CLIENT_HELLO retransmission)
        PendingHandshake pending = sessionManager.findPendingHandshake(from);
        if (pending != null)
        {
            LOG.debug("Resending ServerHello to {} (ClientHello retransmit)", from);
            sendPacket(pending.getServerHelloBuffer(), from);
            return;
        }

        // Check for existing session (new connection from same address)
        DefaultSession existingSession = sessionManager.findByAddress(from);
        if (existingSession != null)
        {
            LOG.info("ClientHello from address with existing session {} - starting new handshake (newest wins)",
                    existingSession.getId());
        }

        // Start new handshake
        ClientHello clientHello = PacketCodec.decodeClientHello(data);
        handshakeHandler.handleClientHello(clientHello, from);
    }

    /**
     * Handles ENCRYPTED (0x03) packets.
     */
    private void handleEncryptedPacket(ByteBuffer data, SocketAddress from)
    {
        // Check for pending handshake (expecting CONNECT)
        PendingHandshake pending = sessionManager.findPendingHandshake(from);
        if (pending != null)
        {
            handlePendingHandshakeEncrypted(data, from, pending);
            return;
        }

        // Check for existing session
        DefaultSession session = sessionManager.findByAddress(from);
        if (session != null)
        {
            handleSessionEncrypted(data, from, session);
            return;
        }

        // No context for this encrypted packet
        LOG.debug("Ignoring encrypted packet from unknown address {}", from);
    }

    /**
     * Handles encrypted packet during pending handshake (expecting CONNECT).
     */
    private void handlePendingHandshakeEncrypted(ByteBuffer data, SocketAddress from, PendingHandshake pending)
    {
        ByteBuffer decrypted;
        try
        {
            decrypted = pending.encryptor().decrypt(data);
        }
        catch (SecurityException e)
        {
            LOG.debug("Failed to decrypt packet from pending handshake {}: {}", from, e.getMessage());
            return;
        }

        PacketType decryptedType = PacketCodec.peekType(decrypted);
        if (decryptedType == PacketType.CONNECT)
        {
            var connect = PacketCodec.decodeConnect(decrypted);
            handshakeHandler.handleConnect(connect, from, pending.encryptor());
        }
        else
        {
            LOG.debug("Expected Connect from pending handshake, got: {}", decryptedType);
        }
    }

    /**
     * Handles encrypted packet for existing session.
     */
    private void handleSessionEncrypted(ByteBuffer data, SocketAddress from, DefaultSession session)
    {
        ByteBuffer decrypted;
        try
        {
            decrypted = session.getEncryptor().decrypt(data);
        }
        catch (SecurityException e)
        {
            LOG.debug("Failed to decrypt packet from session {}: {}", session.getId(), e.getMessage());
            return;
        }

        // Update activity timestamp
        session.updateActivity();

        // Route by inner packet type
        Object packet = PacketCodec.decode(decrypted);
        long nowMs = System.currentTimeMillis();

        switch (packet)
        {
            case Connect c ->
            {
                // Client is retransmitting Connect (Accept was lost) - resend Accept
                LOG.debug("Resending Accept to {} (Connect retransmit)", from);
                resendAccept(session, from);
            }
            case Data d ->
            {
                ReliabilityLayer.ReceiveResult result = session.getReliabilityLayer().receive(d, nowMs);
                for (var msg : result.deliverableMessages())
                {
                    session.enqueue(msg.messageTypeId(), msg.payload());
                    stats.recordMessage();
                }
            }
            case Ack a -> session.getReliabilityLayer().receive(a, nowMs);
            case Heartbeat h -> session.enqueueControl(new QueuedMessage.ControlHeartbeat(h));
            case HeartbeatAck ha -> session.enqueueControl(new QueuedMessage.ControlHeartbeatAck(ha));
            case Disconnect d -> session.enqueueControl(new QueuedMessage.ControlDisconnect(d));
            default -> LOG.debug("Unexpected packet type in session context: {}", packet.getClass().getSimpleName());
        }
    }

    private void resendAccept(DefaultSession session, SocketAddress to)
    {
        Accept accept = new Accept(
                session.getSessionToken(),
                (int) heartbeatInterval.toMillis(),
                (int) sessionTimeout.toMillis(),
                0 // lastReceivedSeq
        );

        ByteBuffer encoded = PacketCodec.encode(accept);
        ByteBuffer encrypted = session.getEncryptor().encrypt(encoded);
        endPoint.send(encrypted, to);
    }

    // ========== Tick Loop ==========

    private void tickLoop()
    {
        LOG.debug("Tick loop started");

        while (running)
        {
            try
            {
                Thread.sleep(heartbeatInterval.toMillis());

                if (!running)
                {
                    break;
                }

                long nowMs = System.currentTimeMillis();

                // Enqueue tick to all sessions
                for (DefaultSession session : sessionManager.getAllSessions())
                {
                    session.enqueueControl(new QueuedMessage.ControlTick(nowMs));
                }

                // Check expired sessions
                List<DefaultSession> expired = sessionManager.checkExpiredSessions(nowMs);
                for (DefaultSession session : expired)
                {
                    for (Consumer<Session> callback : sessionExpiredCallbacks)
                    {
                        try
                        {
                            callback.accept(session);
                        }
                        catch (Exception e)
                        {
                            LOG.error("Session expired callback error", e);
                        }
                    }
                }

                // Check handshake timeouts
                sessionManager.checkHandshakeTimeouts(nowMs);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                break;
            }
            catch (Exception e)
            {
                LOG.error("Error in tick loop", e);
            }
        }

        LOG.debug("Tick loop stopped");
    }

    // ========== Internal Callbacks ==========

    private void notifySessionStarted(DefaultSession session)
    {
        for (Consumer<Session> callback : sessionStartedCallbacks)
        {
            try
            {
                callback.accept(session);
            }
            catch (Exception e)
            {
                LOG.error("Session started callback error", e);
            }
        }
    }
}
