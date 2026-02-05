package org.abstractica.clientserver.impl.session;

import org.abstractica.clientserver.Delivery;
import org.abstractica.clientserver.DisconnectReason;
import org.abstractica.clientserver.Session;
import org.abstractica.clientserver.handlers.ErrorHandler;
import org.abstractica.clientserver.handlers.MessageHandler;
import org.abstractica.clientserver.impl.crypto.PacketEncryptor;
import org.abstractica.clientserver.impl.protocol.Data;
import org.abstractica.clientserver.impl.protocol.Disconnect;
import org.abstractica.clientserver.impl.protocol.Heartbeat;
import org.abstractica.clientserver.impl.protocol.HeartbeatAck;
import org.abstractica.clientserver.impl.protocol.PacketCodec;
import org.abstractica.clientserver.impl.reliability.ReliabilityLayer;
import org.abstractica.clientserver.impl.serialization.DefaultProtocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Default implementation of the Session interface.
 *
 * <p>Each session has its own virtual thread that processes messages from
 * its queue, preserving message ordering within the session.</p>
 */
public class DefaultSession implements Session
{
    private static final Logger LOG = LoggerFactory.getLogger(DefaultSession.class);
    private static final int DEFAULT_QUEUE_CAPACITY = 256;

    private final String id;
    private final byte[] sessionToken;
    private final ReliabilityLayer reliabilityLayer;
    private final PacketEncryptor encryptor;
    private final BlockingQueue<QueuedMessage> messageQueue;
    private final SessionCallback callback;
    private final Duration heartbeatInterval;
    private final Duration sessionTimeout;

    private volatile SocketAddress remoteAddress;
    private volatile SessionState state;
    private volatile long lastActivityMs;
    private volatile long lastHeartbeatSentMs;
    private volatile Object attachment;

    private Thread virtualThread;
    private volatile boolean running;

    /**
     * Creates a new session.
     *
     * @param sessionToken      the unique session token (16 bytes)
     * @param remoteAddress     the client's network address
     * @param encryptor         the packet encryptor for this session
     * @param callback          callback to the server
     * @param heartbeatInterval interval between heartbeats
     * @param sessionTimeout    timeout for session expiry
     */
    public DefaultSession(
            byte[] sessionToken,
            SocketAddress remoteAddress,
            PacketEncryptor encryptor,
            SessionCallback callback,
            Duration heartbeatInterval,
            Duration sessionTimeout
    )
    {
        Objects.requireNonNull(sessionToken, "sessionToken");
        Objects.requireNonNull(remoteAddress, "remoteAddress");
        Objects.requireNonNull(encryptor, "encryptor");
        Objects.requireNonNull(callback, "callback");
        Objects.requireNonNull(heartbeatInterval, "heartbeatInterval");
        Objects.requireNonNull(sessionTimeout, "sessionTimeout");

        this.sessionToken = sessionToken.clone();
        this.id = HexFormat.of().formatHex(sessionToken);
        this.remoteAddress = remoteAddress;
        this.encryptor = encryptor;
        this.callback = callback;
        this.heartbeatInterval = heartbeatInterval;
        this.sessionTimeout = sessionTimeout;

        this.reliabilityLayer = new ReliabilityLayer();
        this.messageQueue = new LinkedBlockingQueue<>(DEFAULT_QUEUE_CAPACITY);
        this.state = SessionState.CONNECTED;
        this.lastActivityMs = System.currentTimeMillis();
        this.lastHeartbeatSentMs = 0;
        this.running = false;
    }

    /**
     * Starts the session's processing thread.
     */
    public void start()
    {
        if (running)
        {
            return;
        }
        running = true;
        virtualThread = Thread.ofVirtual()
                .name("session-" + id.substring(0, 8))
                .start(this::processLoop);
    }

    /**
     * Stops the session's processing thread.
     */
    public void stop()
    {
        running = false;
        if (virtualThread != null)
        {
            virtualThread.interrupt();
        }
    }

    // ========== Session Interface ==========

    @Override
    public void send(Object message)
    {
        send(message, Delivery.RELIABLE);
    }

    @Override
    public void send(Object message, Delivery delivery)
    {
        if (state != SessionState.CONNECTED)
        {
            throw new IllegalStateException("Session is disconnected");
        }
        if (!trySend(message, delivery))
        {
            throw new IllegalStateException("Reliable message queue is full");
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

        if (state != SessionState.CONNECTED)
        {
            LOG.debug("Cannot send on disconnected session: {}", id);
            return false;
        }

        DefaultProtocol protocol = callback.getProtocol();
        int typeId = protocol.getTypeId(message.getClass());
        byte[] payload = protocol.encodeMessage((Record) message);
        // Remove the type ID prefix from payload since ReliabilityLayer adds it separately
        byte[] messagePayload = new byte[payload.length - 2];
        System.arraycopy(payload, 2, messagePayload, 0, messagePayload.length);

        long nowMs = System.currentTimeMillis();

        Data packet;
        if (delivery == Delivery.RELIABLE)
        {
            Optional<Data> maybePacket = reliabilityLayer.sendReliable(typeId, messagePayload, nowMs);
            if (maybePacket.isEmpty())
            {
                return false; // Backpressure
            }
            packet = maybePacket.get();
        }
        else
        {
            packet = reliabilityLayer.sendUnreliable(typeId, messagePayload, nowMs);
        }

        sendDataPacket(packet);
        return true;
    }

    @Override
    public void close()
    {
        close("Session closed");
    }

    @Override
    public void close(String reason)
    {
        if (state == SessionState.DISCONNECTED)
        {
            return;
        }

        // Send disconnect packet
        Disconnect disconnect = new Disconnect(Disconnect.DisconnectCode.NORMAL, reason != null ? reason : "");
        ByteBuffer encoded = PacketCodec.encode(disconnect);
        ByteBuffer encrypted = encryptor.encrypt(encoded);
        callback.sendPacket(encrypted, remoteAddress);

        transitionToDisconnected(new DisconnectReason.KickedByServer(reason));
    }

    @Override
    public String getId()
    {
        return id;
    }

    @Override
    public Optional<Object> getAttachment()
    {
        return Optional.ofNullable(attachment);
    }

    @Override
    public void setAttachment(Object attachment)
    {
        this.attachment = attachment;
    }

    // ========== Internal Methods ==========

    /**
     * Enqueues an application message for processing.
     *
     * @param messageTypeId the message type ID
     * @param payload       the message payload
     */
    public void enqueue(int messageTypeId, byte[] payload)
    {
        if (!messageQueue.offer(new QueuedMessage.ApplicationMessage(messageTypeId, payload)))
        {
            LOG.warn("Session {} message queue full, dropping message", id);
        }
    }

    /**
     * Enqueues a control message for processing.
     *
     * @param message the control message
     */
    public void enqueueControl(QueuedMessage message)
    {
        if (!messageQueue.offer(message))
        {
            LOG.warn("Session {} message queue full, dropping control message", id);
        }
    }

    /**
     * Updates the last activity timestamp.
     */
    public void updateActivity()
    {
        this.lastActivityMs = System.currentTimeMillis();
    }

    /**
     * Returns the reliability layer for this session.
     */
    public ReliabilityLayer getReliabilityLayer()
    {
        return reliabilityLayer;
    }

    /**
     * Returns the packet encryptor for this session.
     */
    public PacketEncryptor getEncryptor()
    {
        return encryptor;
    }

    /**
     * Returns the session token.
     */
    public byte[] getSessionToken()
    {
        return sessionToken.clone();
    }

    /**
     * Returns the remote address.
     */
    public SocketAddress getRemoteAddress()
    {
        return remoteAddress;
    }

    /**
     * Updates the remote address (for reconnection).
     */
    public void setRemoteAddress(SocketAddress address)
    {
        this.remoteAddress = address;
    }

    /**
     * Returns the current session state.
     */
    public SessionState getState()
    {
        return state;
    }

    /**
     * Returns the last activity timestamp.
     */
    public long getLastActivityMs()
    {
        return lastActivityMs;
    }

    /**
     * Transitions to connected state (for reconnection).
     */
    public void transitionToConnected()
    {
        this.state = SessionState.CONNECTED;
        this.lastActivityMs = System.currentTimeMillis();
    }

    // ========== Processing Loop ==========

    private void processLoop()
    {
        LOG.debug("Session {} processing started", id);

        while (running && (state == SessionState.CONNECTED || !messageQueue.isEmpty()))
        {
            try
            {
                QueuedMessage msg = messageQueue.poll(
                        heartbeatInterval.toMillis(),
                        TimeUnit.MILLISECONDS
                );

                if (msg == null)
                {
                    // Timeout - treat as tick
                    processTick(System.currentTimeMillis());
                    continue;
                }

                processMessage(msg);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                break;
            }
            catch (Exception e)
            {
                LOG.error("Error processing message in session {}", id, e);
            }
        }

        LOG.debug("Session {} processing stopped", id);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void processMessage(QueuedMessage msg)
    {
        switch (msg)
        {
            case QueuedMessage.ApplicationMessage app ->
            {
                DefaultProtocol protocol = callback.getProtocol();
                // Reconstruct full message bytes with type ID
                byte[] fullMessage = new byte[app.payload().length + 2];
                fullMessage[0] = (byte) (app.messageTypeId() >> 8);
                fullMessage[1] = (byte) app.messageTypeId();
                System.arraycopy(app.payload(), 0, fullMessage, 2, app.payload().length);

                Record decoded = protocol.decodeMessage(fullMessage);
                MessageHandler handler = callback.getHandler(decoded.getClass());

                if (handler != null)
                {
                    try
                    {
                        handler.handle(this, decoded);
                    }
                    catch (Exception e)
                    {
                        ErrorHandler errorHandler = callback.getErrorHandler();
                        if (errorHandler != null)
                        {
                            try
                            {
                                errorHandler.handle(this, decoded, e);
                            }
                            catch (Exception e2)
                            {
                                LOG.error("Error handler threw exception", e2);
                            }
                        }
                        else
                        {
                            LOG.error("Message handler exception: session={}, messageType={}",
                                    id, decoded.getClass().getSimpleName(), e);
                        }
                    }
                }
                else
                {
                    LOG.debug("No handler for message type: {}", decoded.getClass().getSimpleName());
                }
            }
            case QueuedMessage.ControlHeartbeat h -> sendHeartbeatAck(h.heartbeat());
            case QueuedMessage.ControlHeartbeatAck ha -> processHeartbeatAck(ha.ack());
            case QueuedMessage.ControlDisconnect d -> processDisconnect(d.disconnect());
            case QueuedMessage.ControlTick t -> processTick(t.nowMs());
        }
    }

    private void processTick(long nowMs)
    {
        // Check for timeout (no activity from peer)
        if (nowMs - lastActivityMs > sessionTimeout.toMillis())
        {
            if (state == SessionState.CONNECTED)
            {
                LOG.info("Session {} timed out", id);
                transitionToDisconnected(new DisconnectReason.Timeout());
            }
            else
            {
                // Already disconnected and now expired
                LOG.info("Session {} expired", id);
                callback.onSessionExpired(this);
                running = false;
            }
            return;
        }

        // Only do maintenance if connected
        if (state != SessionState.CONNECTED)
        {
            return;
        }

        // Reliability layer tick (retransmits)
        ReliabilityLayer.TickResult result = reliabilityLayer.tick(nowMs);
        for (Data retransmit : result.retransmits())
        {
            sendDataPacket(retransmit);
        }
        for (int expired : result.expired())
        {
            LOG.warn("Message expired: session={}, seq={}", id, expired);
        }

        // Send pending ack if any
        reliabilityLayer.getAckToSend(nowMs).ifPresent(ack ->
        {
            ByteBuffer encoded = PacketCodec.encode(ack);
            ByteBuffer encrypted = encryptor.encrypt(encoded);
            callback.sendPacket(encrypted, remoteAddress);
        });

        // Send heartbeat if needed
        if (nowMs - lastHeartbeatSentMs > heartbeatInterval.toMillis())
        {
            Heartbeat heartbeat = new Heartbeat(nowMs);
            ByteBuffer encoded = PacketCodec.encode(heartbeat);
            ByteBuffer encrypted = encryptor.encrypt(encoded);
            callback.sendPacket(encrypted, remoteAddress);
            lastHeartbeatSentMs = nowMs;
        }
    }

    private void sendHeartbeatAck(Heartbeat heartbeat)
    {
        HeartbeatAck ack = new HeartbeatAck(heartbeat.timestamp(), System.currentTimeMillis());
        ByteBuffer encoded = PacketCodec.encode(ack);
        ByteBuffer encrypted = encryptor.encrypt(encoded);
        callback.sendPacket(encrypted, remoteAddress);
    }

    private void processHeartbeatAck(HeartbeatAck ack)
    {
        long rtt = System.currentTimeMillis() - ack.echoTimestamp();
        reliabilityLayer.updateRtt(rtt);
    }

    private void processDisconnect(Disconnect disconnect)
    {
        LOG.info("Session {} received disconnect: {} - {}",
                id, disconnect.reasonCode(), disconnect.message());

        DisconnectReason reason = switch (disconnect.reasonCode())
        {
            case NORMAL -> new DisconnectReason.KickedByServer(disconnect.message());
            case KICKED -> new DisconnectReason.KickedByServer(disconnect.message());
            case PROTOCOL_ERROR -> new DisconnectReason.ProtocolError(disconnect.message());
            case SHUTDOWN -> new DisconnectReason.ServerShutdown();
        };

        transitionToDisconnected(reason);
    }

    private void transitionToDisconnected(DisconnectReason reason)
    {
        if (state == SessionState.DISCONNECTED)
        {
            return;
        }
        state = SessionState.DISCONNECTED;
        callback.onSessionDisconnected(this, reason);
    }

    private void sendDataPacket(Data packet)
    {
        ByteBuffer encoded = PacketCodec.encode(packet);
        ByteBuffer encrypted = encryptor.encrypt(encoded);
        callback.sendPacket(encrypted, remoteAddress);
    }
}
