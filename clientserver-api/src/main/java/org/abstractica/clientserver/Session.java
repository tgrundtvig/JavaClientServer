package org.abstractica.clientserver;

import java.util.Optional;

/**
 * A logical connection between client and server.
 *
 * <p>Sessions survive physical disconnects. When the network connection
 * drops, the session enters a disconnected state and can be resumed
 * when the client reconnects. Sessions have a configurable timeout
 * after which they expire if not reconnected.</p>
 *
 * <p>Each session has:</p>
 * <ul>
 *   <li>A unique identifier (session token)</li>
 *   <li>Sequence numbers for message ordering</li>
 *   <li>An optional application attachment for custom state</li>
 * </ul>
 */
public interface Session
{
    /**
     * Sends a message with reliable delivery.
     *
     * <p>Equivalent to {@code send(message, Delivery.RELIABLE)}.</p>
     *
     * @param message the message to send
     */
    void send(Object message);

    /**
     * Sends a message with the specified delivery mode.
     *
     * @param message  the message to send
     * @param delivery the delivery mode
     */
    void send(Object message, Delivery delivery);

    /**
     * Attempts to send a message with reliable delivery.
     *
     * <p>Returns false if the reliable message queue is full, indicating
     * backpressure. The application can react accordingly.</p>
     *
     * @param message the message to send
     * @return true if queued, false if queue is full
     */
    boolean trySend(Object message);

    /**
     * Attempts to send a message with the specified delivery mode.
     *
     * <p>For reliable delivery, returns false if the queue is full.
     * For unreliable delivery, always returns true.</p>
     *
     * @param message  the message to send
     * @param delivery the delivery mode
     * @return true if sent/queued, false if reliable queue is full
     */
    boolean trySend(Object message, Delivery delivery);

    /**
     * Closes the session normally.
     */
    void close();

    /**
     * Closes the session with a reason message.
     *
     * <p>The reason is sent to the peer before closing.</p>
     *
     * @param reason the reason for closing
     */
    void close(String reason);

    /**
     * Returns the unique session identifier.
     *
     * @return session ID
     */
    String getId();

    /**
     * Returns the application attachment if set.
     *
     * @return the attachment, or empty if none set
     */
    Optional<Object> getAttachment();

    /**
     * Sets the application attachment.
     *
     * <p>The attachment is application-managed state associated with
     * this session. The library does not interpret or modify it.</p>
     *
     * @param attachment the attachment to set (may be null)
     */
    void setAttachment(Object attachment);
}
