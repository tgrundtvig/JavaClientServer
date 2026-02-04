package org.abstractica.clientserver;

import java.io.IOException;

/**
 * Reason for session disconnection.
 *
 * <p>Sealed interface enabling exhaustive pattern matching on disconnect causes.</p>
 */
public sealed interface DisconnectReason
{
    /**
     * Network-level error occurred.
     *
     * @param cause the underlying I/O exception
     */
    record NetworkError(IOException cause) implements DisconnectReason {}

    /**
     * Connection timed out (no response within expected window).
     */
    record Timeout() implements DisconnectReason {}

    /**
     * Server explicitly disconnected the client.
     *
     * @param message reason provided by server
     */
    record KickedByServer(String message) implements DisconnectReason {}

    /**
     * Protocol error (version mismatch, malformed data).
     *
     * @param details description of the protocol violation
     */
    record ProtocolError(String details) implements DisconnectReason {}

    /**
     * Server is shutting down.
     */
    record ServerShutdown() implements DisconnectReason {}
}
