package org.abstractica.clientserver.handlers;

import org.abstractica.clientserver.Session;

/**
 * Handles incoming messages of a specific type.
 *
 * <p>Message handlers are registered per message type and invoked when
 * a message of that type is received. Handlers are called from network
 * threads; if thread safety is needed, the handler should queue the
 * message for processing elsewhere.</p>
 *
 * @param <T> the message type this handler processes
 */
@FunctionalInterface
public interface MessageHandler<T>
{
    /**
     * Handles an incoming message.
     *
     * @param session the session that received the message
     * @param message the message to handle
     */
    void handle(Session session, T message);
}
