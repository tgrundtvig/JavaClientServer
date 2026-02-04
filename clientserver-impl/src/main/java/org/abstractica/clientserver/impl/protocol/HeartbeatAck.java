package org.abstractica.clientserver.impl.protocol;

/**
 * Heartbeat acknowledgment packet (sent encrypted).
 *
 * <p>Wire format:</p>
 * <pre>
 * [type: 1 byte = 0x31]
 * [echoTimestamp: 8 bytes]
 * [timestamp: 8 bytes]
 * </pre>
 *
 * @param echoTimestamp timestamp from the received Heartbeat
 * @param timestamp     responder's current timestamp in milliseconds
 */
public record HeartbeatAck(
        long echoTimestamp,
        long timestamp
)
{
    /**
     * Creates a heartbeat ack responding to a heartbeat.
     *
     * @param heartbeat the heartbeat to respond to
     * @return new HeartbeatAck packet
     */
    public static HeartbeatAck responding(Heartbeat heartbeat)
    {
        return new HeartbeatAck(heartbeat.timestamp(), System.currentTimeMillis());
    }

    /**
     * Calculates the round-trip time based on the current time.
     *
     * @return RTT in milliseconds
     */
    public long calculateRtt()
    {
        return System.currentTimeMillis() - echoTimestamp;
    }
}
