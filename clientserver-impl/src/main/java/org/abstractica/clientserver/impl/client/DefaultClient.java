package org.abstractica.clientserver.impl.client;

import org.abstractica.clientserver.Client;
import org.abstractica.clientserver.ClientStats;
import org.abstractica.clientserver.Delivery;
import org.abstractica.clientserver.DisconnectReason;
import org.abstractica.clientserver.Session;
import org.abstractica.clientserver.handlers.ErrorHandler;
import org.abstractica.clientserver.handlers.MessageHandler;
import org.abstractica.clientserver.impl.crypto.KeyExchange;
import org.abstractica.clientserver.impl.crypto.PacketEncryptor;
import org.abstractica.clientserver.impl.crypto.Signer;
import org.abstractica.clientserver.impl.protocol.Accept;
import org.abstractica.clientserver.impl.protocol.Ack;
import org.abstractica.clientserver.impl.protocol.ClientHello;
import org.abstractica.clientserver.impl.protocol.Data;
import org.abstractica.clientserver.impl.protocol.Disconnect;
import org.abstractica.clientserver.impl.protocol.Heartbeat;
import org.abstractica.clientserver.impl.protocol.HeartbeatAck;
import org.abstractica.clientserver.impl.protocol.Connect;
import org.abstractica.clientserver.impl.protocol.PacketCodec;
import org.abstractica.clientserver.impl.protocol.PacketType;
import org.abstractica.clientserver.impl.protocol.Reject;
import org.abstractica.clientserver.impl.protocol.ServerHello;
import org.abstractica.clientserver.impl.reliability.ReliabilityLayer;
import org.abstractica.clientserver.impl.serialization.DefaultProtocol;
import org.abstractica.clientserver.impl.transport.UdpTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.security.PublicKey;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Default implementation of the Client interface.
 *
 * <p>Manages connection to a server, handles handshake, and provides
 * message sending/receiving capabilities.</p>
 */
public class DefaultClient implements Client
{
    private static final Logger LOG = LoggerFactory.getLogger(DefaultClient.class);
    private static final int PROTOCOL_VERSION = 1;
    private static final int QUEUE_CAPACITY = 256;

    private final InetSocketAddress serverAddress;
    private final DefaultProtocol protocol;
    private final PublicKey serverPublicKey;
    private final UdpTransport transport;
    private final DefaultClientStats stats;

    private final Map<Class<?>, MessageHandler<?>> messageHandlers;
    private final List<Consumer<Session>> connectedCallbacks;
    private final List<BiConsumer<Session, DisconnectReason>> disconnectedCallbacks;
    private final List<Consumer<Session>> reconnectedCallbacks;
    private final List<Consumer<DisconnectReason>> connectionFailedCallbacks;
    private ErrorHandler errorHandler;

    // Connection state
    private volatile ClientState state;
    private KeyExchange keyExchange;
    private PacketEncryptor encryptor;
    private byte[] sessionToken;
    private ReliabilityLayer reliabilityLayer;
    private ClientSession session;

    // Threading
    private final BlockingQueue<QueuedItem> inboundQueue;
    private Thread processingThread;
    private Thread tickThread;
    private volatile boolean running;

    // Timing
    private Duration heartbeatInterval = Duration.ofSeconds(5);
    private Duration sessionTimeout = Duration.ofMinutes(2);
    private volatile long lastActivityMs;
    private volatile long lastHeartbeatSentMs;

    /**
     * Connection states.
     */
    private enum ClientState
    {
        DISCONNECTED,
        CONNECTING,
        AWAITING_SERVER_HELLO,
        AWAITING_ACCEPT,
        CONNECTED
    }

    /**
     * Items queued for processing.
     */
    private sealed interface QueuedItem
    {
        record InboundPacket(ByteBuffer data) implements QueuedItem {}
        record Tick(long nowMs) implements QueuedItem {}
    }

    /**
     * Creates a new client.
     */
    DefaultClient(
            InetSocketAddress serverAddress,
            DefaultProtocol protocol,
            PublicKey serverPublicKey
    )
    {
        this.serverAddress = Objects.requireNonNull(serverAddress, "serverAddress");
        this.protocol = Objects.requireNonNull(protocol, "protocol");
        this.serverPublicKey = Objects.requireNonNull(serverPublicKey, "serverPublicKey");

        this.transport = UdpTransport.client();
        this.stats = new DefaultClientStats();

        this.messageHandlers = new ConcurrentHashMap<>();
        this.connectedCallbacks = new ArrayList<>();
        this.disconnectedCallbacks = new ArrayList<>();
        this.reconnectedCallbacks = new ArrayList<>();
        this.connectionFailedCallbacks = new ArrayList<>();

        this.inboundQueue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
        this.state = ClientState.DISCONNECTED;
        this.running = false;
    }

    // ========== Client Interface ==========

    @Override
    public void connect()
    {
        if (state != ClientState.DISCONNECTED)
        {
            throw new IllegalStateException("Already connecting or connected");
        }

        LOG.info("Connecting to {}", serverAddress);

        state = ClientState.CONNECTING;
        running = true;

        // Start transport
        transport.setReceiveHandler(this::onReceive);
        transport.start();

        // Start processing thread
        processingThread = Thread.ofVirtual()
                .name("client-processor")
                .start(this::processingLoop);

        // Start tick thread
        tickThread = Thread.ofVirtual()
                .name("client-tick")
                .start(this::tickLoop);

        // Initiate handshake
        initiateHandshake();
    }

    @Override
    public void disconnect()
    {
        if (state == ClientState.DISCONNECTED)
        {
            return;
        }

        LOG.info("Disconnecting");

        // Send disconnect packet if connected
        if (state == ClientState.CONNECTED && encryptor != null)
        {
            Disconnect disconnect = new Disconnect(Disconnect.DisconnectCode.NORMAL, "Client disconnect");
            ByteBuffer encoded = PacketCodec.encode(disconnect);
            ByteBuffer encrypted = encryptor.encrypt(encoded);
            transport.send(encrypted, serverAddress);
        }

        shutdown(null);
    }

    @Override
    public void close()
    {
        disconnect();
    }

    @Override
    public void send(Object message)
    {
        send(message, Delivery.RELIABLE);
    }

    @Override
    public void send(Object message, Delivery delivery)
    {
        if (!trySend(message, delivery))
        {
            throw new IllegalStateException("Message queue is full or not connected");
        }
    }

    @Override
    public boolean trySend(Object message)
    {
        return trySend(message, Delivery.RELIABLE);
    }

    @Override
    public boolean trySend(Object message, Delivery delivery)
    {
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(delivery, "delivery");

        if (state != ClientState.CONNECTED)
        {
            return false;
        }

        int typeId = protocol.getTypeId(message.getClass());
        byte[] fullPayload = protocol.encodeMessage((Record) message);
        // Remove type ID prefix - reliability layer handles it separately
        byte[] messagePayload = new byte[fullPayload.length - 2];
        System.arraycopy(fullPayload, 2, messagePayload, 0, messagePayload.length);

        long nowMs = System.currentTimeMillis();

        Data packet;
        if (delivery == Delivery.RELIABLE)
        {
            Optional<Data> maybePacket = reliabilityLayer.sendReliable(typeId, messagePayload, nowMs);
            if (maybePacket.isEmpty())
            {
                return false;
            }
            packet = maybePacket.get();
        }
        else
        {
            packet = reliabilityLayer.sendUnreliable(typeId, messagePayload, nowMs);
        }

        sendEncrypted(packet);
        return true;
    }

    @Override
    public <T> void onMessage(Class<T> type, MessageHandler<T> handler)
    {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(handler, "handler");
        messageHandlers.put(type, handler);
    }

    @Override
    public void onConnected(Consumer<Session> handler)
    {
        Objects.requireNonNull(handler, "handler");
        connectedCallbacks.add(handler);
    }

    @Override
    public void onDisconnected(BiConsumer<Session, DisconnectReason> handler)
    {
        Objects.requireNonNull(handler, "handler");
        disconnectedCallbacks.add(handler);
    }

    @Override
    public void onReconnected(Consumer<Session> handler)
    {
        Objects.requireNonNull(handler, "handler");
        reconnectedCallbacks.add(handler);
    }

    @Override
    public void onConnectionFailed(Consumer<DisconnectReason> handler)
    {
        Objects.requireNonNull(handler, "handler");
        connectionFailedCallbacks.add(handler);
    }

    @Override
    public void onError(ErrorHandler handler)
    {
        this.errorHandler = handler;
    }

    @Override
    public Session getSession()
    {
        return session;
    }

    @Override
    public ClientStats getStats()
    {
        return stats;
    }

    // ========== Handshake ==========

    private void initiateHandshake()
    {
        // Generate ephemeral key pair
        keyExchange = new KeyExchange();

        // Send ClientHello
        ClientHello clientHello = new ClientHello(PROTOCOL_VERSION, keyExchange.getPublicKey());
        ByteBuffer encoded = PacketCodec.encode(clientHello);
        transport.send(encoded, serverAddress);

        state = ClientState.AWAITING_SERVER_HELLO;
        lastActivityMs = System.currentTimeMillis();

        LOG.debug("ClientHello sent");
    }

    private void handleServerHello(ServerHello serverHello)
    {
        if (state != ClientState.AWAITING_SERVER_HELLO)
        {
            LOG.warn("Unexpected ServerHello in state {}", state);
            return;
        }

        LOG.debug("ServerHello received");

        // Verify server signature
        byte[] serverEphemeralPublic = serverHello.serverPublicKey();
        boolean valid = Signer.verify(serverEphemeralPublic, serverHello.signature(), serverPublicKey);

        if (!valid)
        {
            LOG.error("Server signature verification failed");
            notifyConnectionFailed(new DisconnectReason.ProtocolError("Server signature invalid"));
            shutdown(null);
            return;
        }

        // Compute shared secret and derive keys
        byte[] sharedSecret = keyExchange.computeSharedSecret(serverEphemeralPublic);
        KeyExchange.DerivedKeys derivedKeys = KeyExchange.deriveKeys(sharedSecret);
        encryptor = new PacketEncryptor(derivedKeys);

        // Initialize reliability layer
        reliabilityLayer = new ReliabilityLayer();

        // Send Connect
        Connect connect;
        if (sessionToken != null)
        {
            // Reconnecting
            connect = Connect.reconnect(protocol.getHashBytes(), sessionToken, 0);
        }
        else
        {
            // New connection
            connect = Connect.newConnection(protocol.getHashBytes());
        }

        sendEncrypted(connect);
        state = ClientState.AWAITING_ACCEPT;

        LOG.debug("Connect sent");
    }

    private void handleAccept(Accept accept)
    {
        if (state != ClientState.AWAITING_ACCEPT)
        {
            LOG.warn("Unexpected Accept in state {}", state);
            return;
        }

        LOG.info("Connection accepted: token={}", bytesToHex(accept.sessionToken()));

        boolean isReconnect = sessionToken != null;
        sessionToken = accept.sessionToken();
        heartbeatInterval = Duration.ofMillis(accept.heartbeatIntervalMs());
        sessionTimeout = Duration.ofMillis(accept.sessionTimeoutMs());

        // Create session wrapper
        session = new ClientSession(this);

        state = ClientState.CONNECTED;
        lastActivityMs = System.currentTimeMillis();

        // Notify callbacks
        if (isReconnect)
        {
            for (Consumer<Session> callback : reconnectedCallbacks)
            {
                safeCallback(() -> callback.accept(session));
            }
        }
        else
        {
            for (Consumer<Session> callback : connectedCallbacks)
            {
                safeCallback(() -> callback.accept(session));
            }
        }
    }

    private void handleReject(Reject reject)
    {
        LOG.warn("Connection rejected: {} - {}", reject.reasonCode(), reject.message());

        DisconnectReason reason = switch (reject.reasonCode())
        {
            case PROTOCOL_MISMATCH -> new DisconnectReason.ProtocolError(reject.message());
            case SERVER_FULL -> new DisconnectReason.KickedByServer("Server full: " + reject.message());
            case SESSION_EXPIRED -> new DisconnectReason.Timeout();
            case INVALID_TOKEN -> new DisconnectReason.ProtocolError("Invalid token: " + reject.message());
            case AUTHENTICATION_FAILED -> new DisconnectReason.ProtocolError("Auth failed: " + reject.message());
        };

        notifyConnectionFailed(reason);
        shutdown(null);
    }

    // ========== Packet Handling ==========

    private void onReceive(ByteBuffer data, SocketAddress from)
    {
        if (!from.equals(serverAddress))
        {
            LOG.debug("Ignoring packet from unknown source: {}", from);
            return;
        }

        // Copy the buffer data for queuing
        byte[] copy = new byte[data.remaining()];
        data.get(copy);

        if (!inboundQueue.offer(new QueuedItem.InboundPacket(ByteBuffer.wrap(copy))))
        {
            LOG.warn("Inbound queue full, dropping packet");
        }
    }

    private void processingLoop()
    {
        LOG.debug("Processing loop started");

        while (running)
        {
            try
            {
                QueuedItem item = inboundQueue.poll(100, TimeUnit.MILLISECONDS);
                if (item == null)
                {
                    continue;
                }

                switch (item)
                {
                    case QueuedItem.InboundPacket p -> processPacket(p.data());
                    case QueuedItem.Tick t -> processTick(t.nowMs());
                }
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                break;
            }
            catch (Exception e)
            {
                LOG.error("Error in processing loop", e);
            }
        }

        LOG.debug("Processing loop stopped");
    }

    private void processPacket(ByteBuffer data)
    {
        if (data.remaining() < 1)
        {
            return;
        }

        // Check for unencrypted ServerHello (0x02) - the only unencrypted packet client receives
        int firstByte = data.get(data.position()) & 0xFF;

        if (firstByte == PacketType.SERVER_HELLO.getId())
        {
            ServerHello serverHello = PacketCodec.decodeServerHello(data);
            handleServerHello(serverHello);
            return;
        }

        // All other packets are encrypted
        if (encryptor == null)
        {
            LOG.debug("Encrypted packet received but no encryptor available");
            return;
        }

        ByteBuffer decrypted;
        try
        {
            decrypted = encryptor.decrypt(data);
        }
        catch (SecurityException e)
        {
            LOG.warn("Decryption failed: {}", e.getMessage());
            return;
        }

        lastActivityMs = System.currentTimeMillis();

        Object packet = PacketCodec.decode(decrypted);
        long nowMs = System.currentTimeMillis();

        switch (packet)
        {
            case Accept a -> handleAccept(a);
            case Reject r -> handleReject(r);
            case Data d -> handleData(d, nowMs);
            case Ack a -> reliabilityLayer.receive(a, nowMs);
            case Heartbeat h -> handleHeartbeat(h);
            case HeartbeatAck ha -> handleHeartbeatAck(ha);
            case Disconnect d -> handleDisconnect(d);
            default -> LOG.debug("Unexpected packet: {}", packet.getClass().getSimpleName());
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void handleData(Data data, long nowMs)
    {
        ReliabilityLayer.ReceiveResult result = reliabilityLayer.receive(data, nowMs);

        for (var msg : result.deliverableMessages())
        {
            stats.recordMessage();

            // Reconstruct full message with type ID
            byte[] fullMessage = new byte[msg.payload().length + 2];
            fullMessage[0] = (byte) (msg.messageTypeId() >> 8);
            fullMessage[1] = (byte) msg.messageTypeId();
            System.arraycopy(msg.payload(), 0, fullMessage, 2, msg.payload().length);

            Record decoded = protocol.decodeMessage(fullMessage);
            MessageHandler handler = messageHandlers.get(decoded.getClass());

            if (handler != null)
            {
                try
                {
                    handler.handle(session, decoded);
                }
                catch (Exception e)
                {
                    if (errorHandler != null)
                    {
                        safeCallback(() -> errorHandler.handle(session, decoded, e));
                    }
                    else
                    {
                        LOG.error("Message handler exception", e);
                    }
                }
            }
            else
            {
                LOG.debug("No handler for message type: {}", decoded.getClass().getSimpleName());
            }
        }
    }

    private void handleHeartbeat(Heartbeat heartbeat)
    {
        HeartbeatAck ack = HeartbeatAck.responding(heartbeat);
        sendEncrypted(ack);
    }

    private void handleHeartbeatAck(HeartbeatAck ack)
    {
        long rtt = ack.calculateRtt();
        reliabilityLayer.updateRtt(rtt);
        stats.updateRtt(Duration.ofMillis(rtt));
    }

    private void handleDisconnect(Disconnect disconnect)
    {
        LOG.info("Server disconnected: {} - {}", disconnect.reasonCode(), disconnect.message());

        DisconnectReason reason = switch (disconnect.reasonCode())
        {
            case NORMAL -> new DisconnectReason.KickedByServer(disconnect.message());
            case KICKED -> new DisconnectReason.KickedByServer(disconnect.message());
            case PROTOCOL_ERROR -> new DisconnectReason.ProtocolError(disconnect.message());
            case SHUTDOWN -> new DisconnectReason.ServerShutdown();
        };

        shutdown(reason);
    }

    // ========== Tick ==========

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

                inboundQueue.offer(new QueuedItem.Tick(System.currentTimeMillis()));
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                break;
            }
        }

        LOG.debug("Tick loop stopped");
    }

    private void processTick(long nowMs)
    {
        if (state != ClientState.CONNECTED)
        {
            // Check handshake timeout
            if (state == ClientState.AWAITING_SERVER_HELLO || state == ClientState.AWAITING_ACCEPT)
            {
                if (nowMs - lastActivityMs > Duration.ofSeconds(30).toMillis())
                {
                    LOG.warn("Handshake timeout");
                    notifyConnectionFailed(new DisconnectReason.Timeout());
                    shutdown(null);
                }
            }
            return;
        }

        // Check for connection timeout
        if (nowMs - lastActivityMs > sessionTimeout.toMillis())
        {
            LOG.warn("Connection timeout");
            shutdown(new DisconnectReason.Timeout());
            return;
        }

        // Reliability tick (retransmits)
        ReliabilityLayer.TickResult result = reliabilityLayer.tick(nowMs);
        for (Data retransmit : result.retransmits())
        {
            sendEncrypted(retransmit);
            stats.recordPacket(true);
        }

        // Send pending ack
        reliabilityLayer.getAckToSend(nowMs).ifPresent(this::sendEncrypted);

        // Send heartbeat
        if (nowMs - lastHeartbeatSentMs > heartbeatInterval.toMillis())
        {
            sendEncrypted(Heartbeat.now());
            lastHeartbeatSentMs = nowMs;
        }
    }

    // ========== Helpers ==========

    private void sendEncrypted(Object packet)
    {
        ByteBuffer encoded = switch (packet)
        {
            case Connect c -> PacketCodec.encode(c);
            case Data d -> PacketCodec.encode(d);
            case Ack a -> PacketCodec.encode(a);
            case Heartbeat h -> PacketCodec.encode(h);
            case HeartbeatAck ha -> PacketCodec.encode(ha);
            case Disconnect d -> PacketCodec.encode(d);
            default -> throw new IllegalArgumentException("Unknown packet type: " + packet.getClass());
        };

        ByteBuffer encrypted = encryptor.encrypt(encoded);
        transport.send(encrypted, serverAddress);
        stats.recordBytes(encrypted.remaining());
        stats.recordPacket(false);
    }

    private void shutdown(DisconnectReason reason)
    {
        if (state == ClientState.DISCONNECTED)
        {
            return;
        }

        ClientState previousState = state;
        state = ClientState.DISCONNECTED;
        running = false;

        // Interrupt threads
        if (processingThread != null)
        {
            processingThread.interrupt();
        }
        if (tickThread != null)
        {
            tickThread.interrupt();
        }

        // Close transport
        transport.close();

        // Notify callbacks
        if (reason != null && previousState == ClientState.CONNECTED && session != null)
        {
            for (BiConsumer<Session, DisconnectReason> callback : disconnectedCallbacks)
            {
                safeCallback(() -> callback.accept(session, reason));
            }
        }

        session = null;
        encryptor = null;
        reliabilityLayer = null;
    }

    private void notifyConnectionFailed(DisconnectReason reason)
    {
        for (Consumer<DisconnectReason> callback : connectionFailedCallbacks)
        {
            safeCallback(() -> callback.accept(reason));
        }
    }

    private void safeCallback(Runnable callback)
    {
        try
        {
            callback.run();
        }
        catch (Exception e)
        {
            LOG.error("Callback error", e);
        }
    }

    private static String bytesToHex(byte[] bytes)
    {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes)
        {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
