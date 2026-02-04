package org.abstractica.clientserver.impl.protocol;

import java.util.Objects;

/**
 * Server's session acceptance response (sent encrypted).
 *
 * <p>Wire format:</p>
 * <pre>
 * [type: 1 byte = 0x11]
 * [sessionToken: 16 bytes]
 * [heartbeatIntervalMs: 4 bytes]
 * [sessionTimeoutMs: 4 bytes]
 * [lastReceivedSeq: 4 bytes]
 * </pre>
 *
 * @param sessionToken        the session identifier (new or confirmed)
 * @param heartbeatIntervalMs heartbeat interval in milliseconds
 * @param sessionTimeoutMs    session timeout in milliseconds
 * @param lastReceivedSeq     server's last received sequence (for sync)
 */
public record Accept(
        byte[] sessionToken,
        int heartbeatIntervalMs,
        int sessionTimeoutMs,
        int lastReceivedSeq
)
{
    public static final int SESSION_TOKEN_LENGTH = 16;

    public Accept
    {
        Objects.requireNonNull(sessionToken, "sessionToken");
        if (sessionToken.length != SESSION_TOKEN_LENGTH)
        {
            throw new IllegalArgumentException(
                    "Session token must be " + SESSION_TOKEN_LENGTH + " bytes: " + sessionToken.length);
        }
        if (heartbeatIntervalMs <= 0)
        {
            throw new IllegalArgumentException(
                    "Heartbeat interval must be positive: " + heartbeatIntervalMs);
        }
        if (sessionTimeoutMs <= 0)
        {
            throw new IllegalArgumentException(
                    "Session timeout must be positive: " + sessionTimeoutMs);
        }
    }
}
