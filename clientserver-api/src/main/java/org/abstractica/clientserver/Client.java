package org.abstractica.clientserver;

import org.abstractica.clientserver.handlers.ErrorHandler;
import org.abstractica.clientserver.handlers.MessageHandler;

import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * A client that connects to a server.
 *
 * <p>The client handles the network layer, encryption, reliability,
 * and session management. Applications register message handlers
 * and lifecycle callbacks to process incoming data.</p>
 *
 * <p>Example usage:</p>
 * <pre>{@code
 * Client client = clientFactory.builder()
 *     .serverAddress("game.example.com", 7777)
 *     .protocol(protocol)
 *     .serverPublicKey(serverPublicKey)
 *     .build();
 *
 * client.onMessage(Welcome.class, (session, msg) -> {
 *     // Handle welcome message
 * });
 *
 * client.onConnected(session -> {
 *     session.send(new Join("Alice"));
 * });
 *
 * client.connect();
 * }</pre>
 */
public interface Client extends AutoCloseable
{
    /**
     * Connects to the server.
     *
     * <p>Initiates the connection asynchronously. Connection result is
     * delivered via {@link #onConnected} or {@link #onConnectionFailed}
     * callbacks.</p>
     */
    void connect();

    /**
     * Disconnects from the server.
     *
     * <p>Closes the connection gracefully. The session on the server
     * side may survive for reconnection if timeout allows.</p>
     */
    void disconnect();

    /**
     * Closes the client.
     *
     * <p>Equivalent to {@link #disconnect()}.</p>
     */
    @Override
    void close();

    /**
     * Sends a message to the server with reliable delivery.
     *
     * @param message the message to send
     */
    void send(Object message);

    /**
     * Sends a message to the server.
     *
     * @param message  the message to send
     * @param delivery the delivery mode
     */
    void send(Object message, Delivery delivery);

    /**
     * Attempts to send a message with reliable delivery.
     *
     * <p>Returns false if the reliable message queue is full.</p>
     *
     * @param message the message to send
     * @return true if queued, false if queue is full
     */
    boolean trySend(Object message);

    /**
     * Attempts to send a message with the specified delivery mode.
     *
     * @param message  the message to send
     * @param delivery the delivery mode
     * @return true if sent/queued, false if reliable queue is full
     */
    boolean trySend(Object message, Delivery delivery);

    /**
     * Registers a handler for messages of the specified type.
     *
     * @param type    the message class to handle
     * @param handler the handler to invoke
     * @param <T>     the message type
     */
    <T> void onMessage(Class<T> type, MessageHandler<T> handler);

    /**
     * Registers a callback for successful connection.
     *
     * @param handler called when connection is established
     */
    void onConnected(Consumer<Session> handler);

    /**
     * Registers a callback for disconnection.
     *
     * @param handler called with the session and disconnect reason
     */
    void onDisconnected(BiConsumer<Session, DisconnectReason> handler);

    /**
     * Registers a callback for reconnection.
     *
     * @param handler called when a dropped connection is restored
     */
    void onReconnected(Consumer<Session> handler);

    /**
     * Registers a callback for connection failure.
     *
     * <p>Called when the initial connection attempt fails.</p>
     *
     * @param handler called with the failure reason
     */
    void onConnectionFailed(Consumer<DisconnectReason> handler);

    /**
     * Registers an error handler for message handler exceptions.
     *
     * @param handler called when a message handler throws an exception
     */
    void onError(ErrorHandler handler);

    /**
     * Returns the current session if connected.
     *
     * @return the session, or empty if not connected
     */
    Optional<Session> getSession();

    /**
     * Returns client statistics.
     *
     * @return current statistics snapshot
     */
    ClientStats getStats();
}
