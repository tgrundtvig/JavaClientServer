package org.abstractica.clientserver;

import org.abstractica.clientserver.handlers.ErrorHandler;
import org.abstractica.clientserver.handlers.MessageHandler;

import java.util.Collection;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * A server that accepts client connections and manages sessions.
 *
 * <p>The server handles the network layer, encryption, reliability,
 * and session management. Applications register message handlers
 * and lifecycle callbacks to process incoming data.</p>
 *
 * <p>Example usage:</p>
 * <pre>{@code
 * Server server = serverFactory.builder()
 *     .port(7777)
 *     .protocol(protocol)
 *     .privateKey(privateKey)
 *     .build();
 *
 * server.onMessage(Join.class, (session, msg) -> {
 *     // Handle join message
 * });
 *
 * server.onSessionStarted(session -> {
 *     // New session connected
 * });
 *
 * server.start();
 * }</pre>
 */
public interface Server extends AutoCloseable
{
    /**
     * Starts the server.
     *
     * <p>Begins accepting connections. This method returns immediately;
     * the server runs on background threads.</p>
     */
    void start();

    /**
     * Stops accepting new connections.
     *
     * <p>Existing sessions remain active. Use this for graceful shutdown:
     * stop accepting, notify clients, wait, then close.</p>
     */
    void stop();

    /**
     * Closes the server and all sessions.
     */
    @Override
    void close();

    /**
     * Registers a handler for messages of the specified type.
     *
     * <p>Handlers are called from network threads. If thread safety is
     * needed, the handler should queue messages for processing elsewhere.</p>
     *
     * @param type    the message class to handle
     * @param handler the handler to invoke
     * @param <T>     the message type
     */
    <T> void onMessage(Class<T> type, MessageHandler<T> handler);

    /**
     * Registers a callback for new session connections.
     *
     * @param handler called when a new session is established
     */
    void onSessionStarted(Consumer<Session> handler);

    /**
     * Registers a callback for session disconnections.
     *
     * <p>Called when a session's connection drops. The session may
     * reconnect before expiring.</p>
     *
     * @param handler called with the session and disconnect reason
     */
    void onSessionDisconnected(BiConsumer<Session, DisconnectReason> handler);

    /**
     * Registers a callback for session reconnections.
     *
     * @param handler called when a disconnected session reconnects
     */
    void onSessionReconnected(Consumer<Session> handler);

    /**
     * Registers a callback for session expiration.
     *
     * <p>Called when a disconnected session times out without reconnecting.</p>
     *
     * @param handler called when a session expires
     */
    void onSessionExpired(Consumer<Session> handler);

    /**
     * Registers an error handler for message handler exceptions.
     *
     * @param handler called when a message handler throws an exception
     */
    void onError(ErrorHandler handler);

    /**
     * Broadcasts a message to all connected sessions with reliable delivery.
     *
     * @param message the message to broadcast
     */
    void broadcast(Object message);

    /**
     * Broadcasts a message to all connected sessions.
     *
     * @param message  the message to broadcast
     * @param delivery the delivery mode
     */
    void broadcast(Object message, Delivery delivery);

    /**
     * Returns all active sessions.
     *
     * @return unmodifiable collection of sessions
     */
    Collection<Session> getSessions();

    /**
     * Returns server statistics.
     *
     * @return current statistics snapshot
     */
    ServerStats getStats();
}
