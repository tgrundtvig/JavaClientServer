package org.abstractica.clientserver.impl.client;

import org.abstractica.clientserver.ClientStats;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Default implementation of ClientStats.
 */
public class DefaultClientStats implements ClientStats
{
    private final AtomicLong messagesProcessed = new AtomicLong(0);
    private final AtomicLong bytesTransferred = new AtomicLong(0);
    private final AtomicLong retransmits = new AtomicLong(0);
    private final AtomicLong totalPackets = new AtomicLong(0);

    private volatile long lastStatTimeMs = System.currentTimeMillis();
    private volatile long lastMessagesProcessed = 0;
    private volatile long lastBytesTransferred = 0;
    private volatile Duration currentRtt = Duration.ZERO;

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
    public Duration getRtt()
    {
        return currentRtt;
    }

    // ========== Update Methods ==========

    public void recordMessage()
    {
        messagesProcessed.incrementAndGet();
    }

    public void recordBytes(long bytes)
    {
        bytesTransferred.addAndGet(bytes);
    }

    public void recordPacket(boolean isRetransmit)
    {
        totalPackets.incrementAndGet();
        if (isRetransmit)
        {
            retransmits.incrementAndGet();
        }
    }

    public void updateRtt(Duration rtt)
    {
        this.currentRtt = rtt;
    }
}
