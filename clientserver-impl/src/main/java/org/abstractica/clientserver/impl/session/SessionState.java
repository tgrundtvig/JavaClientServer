package org.abstractica.clientserver.impl.session;

/**
 * State of a session.
 */
public enum SessionState
{
    /**
     * Session is active with a working network connection.
     */
    CONNECTED,

    /**
     * Session exists but network connection is lost.
     * Session may reconnect before expiring.
     */
    DISCONNECTED
}
