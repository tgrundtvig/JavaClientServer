package org.abstractica.clientserver.handlers;

import org.abstractica.clientserver.Session;

/**
 * Handles exceptions thrown by message handlers.
 *
 * <p>When a message handler throws an exception, the library catches it,
 * logs it, and invokes this error handler. The session continues processing
 * other messages; one buggy handler should not crash the server.</p>
 */
@FunctionalInterface
public interface ErrorHandler
{
    /**
     * Handles an exception thrown by a message handler.
     *
     * @param session   the session where the error occurred
     * @param message   the message that caused the error
     * @param exception the exception thrown by the handler
     */
    void handle(Session session, Object message, Exception exception);
}
