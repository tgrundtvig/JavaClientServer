package org.abstractica.clientserver;

import java.time.Duration;

/**
 * Server statistics for monitoring and observability.
 *
 * <p>Statistics are pollable snapshots. The application can query these
 * values and push to a monitoring system of choice.</p>
 */
public interface ServerStats
{
    /**
     * Returns the number of currently connected clients.
     *
     * @return active connection count
     */
    int getActiveConnections();

    /**
     * Returns the number of active sessions (including disconnected but not expired).
     *
     * @return active session count
     */
    int getActiveSessions();

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
     * Returns the average round-trip time across all connections.
     *
     * @return average RTT
     */
    Duration getAverageRtt();
}
