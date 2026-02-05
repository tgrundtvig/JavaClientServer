package org.abstractica.clientserver.impl.session;

import org.abstractica.clientserver.DisconnectReason;
import org.abstractica.clientserver.handlers.ErrorHandler;
import org.abstractica.clientserver.handlers.MessageHandler;
import org.abstractica.clientserver.impl.serialization.DefaultProtocol;

import java.net.SocketAddress;
import java.nio.ByteBuffer;

/**
 * Callback interface from session to server.
 *
 * <p>Used by DefaultSession to send packets, notify lifecycle events,
 * and access shared resources like handlers and protocol.</p>
 */
public interface SessionCallback
{
    /**
     * Sends a packet to a destination address.
     *
     * @param packet      the encrypted packet data
     * @param destination the target address
     */
    void sendPacket(ByteBuffer packet, SocketAddress destination);

    /**
     * Notifies that a session has disconnected.
     *
     * @param session the disconnected session
     * @param reason  the reason for disconnection
     */
    void onSessionDisconnected(DefaultSession session, DisconnectReason reason);

    /**
     * Notifies that a session has expired.
     *
     * @param session the expired session
     */
    void onSessionExpired(DefaultSession session);

    /**
     * Notifies that a session's connection stability has changed.
     *
     * @param session the session whose stability changed
     * @param stable  true if connection became stable, false if unstable
     */
    void onSessionStabilityChanged(DefaultSession session, boolean stable);

    /**
     * Gets the message handler for a message type.
     *
     * @param messageType the message class
     * @return the handler, or null if none registered
     */
    MessageHandler<?> getHandler(Class<?> messageType);

    /**
     * Gets the error handler for handler exceptions.
     *
     * @return the error handler, or null if none set
     */
    ErrorHandler getErrorHandler();

    /**
     * Gets the protocol for message serialization.
     *
     * @return the protocol
     */
    DefaultProtocol getProtocol();
}
