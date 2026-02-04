package org.abstractica.clientserver.impl.session;

import org.abstractica.clientserver.ServerStats;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Default implementation of ServerStats.
 *
 * <p>Provides real-time statistics about server operation.</p>
 */
public class DefaultServerStats implements ServerStats
{
    private final SessionManager sessionManager;
    private final AtomicLong messagesProcessed = new AtomicLong(0);
    private final AtomicLong bytesTransferred = new AtomicLong(0);
    private final AtomicLong retransmits = new AtomicLong(0);
    private final AtomicLong totalPackets = new AtomicLong(0);

    private volatile long lastStatTimeMs = System.currentTimeMillis();
    private volatile long lastMessagesProcessed = 0;
    private volatile long lastBytesTransferred = 0;
    private volatile Duration averageRtt = Duration.ZERO;

    /**
     * Creates stats for a session manager.
     *
     * @param sessionManager the session manager to track
     */
    public DefaultServerStats(SessionManager sessionManager)
    {
        this.sessionManager = sessionManager;
    }

    @Override
    public int getActiveConnections()
    {
        return sessionManager.getConnectedCount();
    }

    @Override
    public int getActiveSessions()
    {
        return sessionManager.getTotalCount();
    }

    @Override
    public long getMessagesPerSecond()
    {
        long now = System.currentTimeMillis();
        long elapsed = now - lastStatTimeMs;
        if (elapsed <= 0)
        {
            return 0;
        }

        long current = messagesProcessed.get();
        long delta = current - lastMessagesProcessed;

        // Update for next call
        lastStatTimeMs = now;
        lastMessagesProcessed = current;

        return (delta * 1000) / elapsed;
    }

    @Override
    public long getBytesPerSecond()
    {
        long now = System.currentTimeMillis();
        long elapsed = now - lastStatTimeMs;
        if (elapsed <= 0)
        {
            return 0;
        }

        long current = bytesTransferred.get();
        long delta = current - lastBytesTransferred;

        lastBytesTransferred = current;

        return (delta * 1000) / elapsed;
    }

    @Override
    public double getRetransmitRate()
    {
        long total = totalPackets.get();
        if (total == 0)
        {
            return 0.0;
        }
        return (double) retransmits.get() / total;
    }

    @Override
    public Duration getAverageRtt()
    {
        return averageRtt;
    }

    // ========== Update Methods ==========

    /**
     * Records a message processed.
     */
    public void recordMessage()
    {
        messagesProcessed.incrementAndGet();
    }

    /**
     * Records bytes transferred.
     *
     * @param bytes number of bytes
     */
    public void recordBytes(long bytes)
    {
        bytesTransferred.addAndGet(bytes);
    }

    /**
     * Records a packet sent.
     *
     * @param isRetransmit whether this is a retransmit
     */
    public void recordPacket(boolean isRetransmit)
    {
        totalPackets.incrementAndGet();
        if (isRetransmit)
        {
            retransmits.incrementAndGet();
        }
    }

    /**
     * Updates the average RTT.
     *
     * @param rtt the RTT sample
     */
    public void updateAverageRtt(Duration rtt)
    {
        // Simple exponential moving average
        long currentMs = averageRtt.toMillis();
        long newMs = rtt.toMillis();
        long updated = (currentMs * 7 + newMs) / 8;
        averageRtt = Duration.ofMillis(updated);
    }
}
