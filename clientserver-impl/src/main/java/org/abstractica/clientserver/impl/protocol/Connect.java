package org.abstractica.clientserver.impl.protocol;

import java.util.Objects;
import java.util.Optional;

/**
 * Client's session establishment request (sent encrypted).
 *
 * <p>Wire format:</p>
 * <pre>
 * [type: 1 byte = 0x10]
 * [protocolHash: 32 bytes]
 * [tokenLength: 1 byte] (0 for new, 16 for reconnect)
 * [sessionToken: 0 or 16 bytes]
 * [lastReceivedSeq: 4 bytes] (only if reconnecting)
 * </pre>
 *
 * @param protocolHash     SHA-256 hash of protocol structure (32 bytes)
 * @param sessionToken     session token for reconnection (empty for new connection)
 * @param lastReceivedSeq  last received sequence number (only present if reconnecting)
 */
public record Connect(
        byte[] protocolHash,
        Optional<byte[]> sessionToken,
        Optional<Integer> lastReceivedSeq
)
{
    public static final int PROTOCOL_HASH_LENGTH = 32;
    public static final int SESSION_TOKEN_LENGTH = 16;

    public Connect
    {
        Objects.requireNonNull(protocolHash, "protocolHash");
        if (protocolHash.length != PROTOCOL_HASH_LENGTH)
        {
            throw new IllegalArgumentException(
                    "Protocol hash must be " + PROTOCOL_HASH_LENGTH + " bytes: " + protocolHash.length);
        }
        Objects.requireNonNull(sessionToken, "sessionToken");
        sessionToken.ifPresent(token ->
        {
            if (token.length != SESSION_TOKEN_LENGTH)
            {
                throw new IllegalArgumentException(
                        "Session token must be " + SESSION_TOKEN_LENGTH + " bytes: " + token.length);
            }
        });
        Objects.requireNonNull(lastReceivedSeq, "lastReceivedSeq");
        if (sessionToken.isEmpty() && lastReceivedSeq.isPresent())
        {
            throw new IllegalArgumentException(
                    "lastReceivedSeq can only be present when reconnecting (sessionToken present)");
        }
    }

    /**
     * Creates a Connect packet for a new connection.
     *
     * @param protocolHash the protocol hash
     * @return new Connect packet
     */
    public static Connect newConnection(byte[] protocolHash)
    {
        return new Connect(protocolHash, Optional.empty(), Optional.empty());
    }

    /**
     * Creates a Connect packet for reconnection.
     *
     * @param protocolHash    the protocol hash
     * @param sessionToken    the session token to resume
     * @param lastReceivedSeq the last received sequence number
     * @return reconnect Connect packet
     */
    public static Connect reconnect(byte[] protocolHash, byte[] sessionToken, int lastReceivedSeq)
    {
        return new Connect(protocolHash, Optional.of(sessionToken), Optional.of(lastReceivedSeq));
    }
}
