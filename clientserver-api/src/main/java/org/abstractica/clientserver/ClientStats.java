package org.abstractica.clientserver;

import java.time.Duration;

/**
 * Client connection statistics for monitoring and observability.
 *
 * <p>Statistics are pollable snapshots. The application can query these
 * values and push to a monitoring system of choice.</p>
 */
public interface ClientStats
{
    /**
     * Returns the current message throughput.
     *
     * @return messages processed per second
     */
    long getMessagesPerSecond();

    /**
     * Returns the current bandwidth usage.
     *
     * @return bytes transferred per second
     */
    long getBytesPerSecond();

    /**
     * Returns the ratio of retransmitted packets to total packets.
     *
     * @return retransmit rate (0.0 to 1.0)
     */
    double getRetransmitRate();

    /**
     * Returns the current round-trip time to the server.
     *
     * @return current RTT
     */
    Duration getRtt();
}
