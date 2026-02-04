package org.abstractica.clientserver.impl.protocol;

/**
 * Heartbeat packet for connection keepalive (sent encrypted).
 *
 * <p>Wire format:</p>
 * <pre>
 * [type: 1 byte = 0x30]
 * [timestamp: 8 bytes]
 * </pre>
 *
 * @param timestamp sender's timestamp in milliseconds
 */
public record Heartbeat(
        long timestamp
)
{
    /**
     * Creates a heartbeat with the current time.
     *
     * @return new Heartbeat packet
     */
    public static Heartbeat now()
    {
        return new Heartbeat(System.currentTimeMillis());
    }
}
