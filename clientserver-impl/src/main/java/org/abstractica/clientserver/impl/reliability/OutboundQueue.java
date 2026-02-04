package org.abstractica.clientserver.impl.reliability;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Tracks reliable messages awaiting acknowledgment.
 *
 * <p>Provides queue management, acknowledgment handling, and retransmission
 * tracking with configurable capacity for backpressure.</p>
 */
public class OutboundQueue
{
    /**
     * Default maximum number of unacknowledged messages.
     */
    public static final int DEFAULT_MAX_SIZE = 256;

    /**
     * Default maximum retransmission attempts before giving up.
     */
    public static final int DEFAULT_MAX_ATTEMPTS = 10;

    /**
     * An entry in the outbound queue.
     *
     * @param sequence       sequence number
     * @param messageTypeId  application message type
     * @param payload        serialized message data
     * @param sentTimeMs     time the message was last sent
     * @param attemptCount   number of transmission attempts
     */
    public record Entry(
            int sequence,
            int messageTypeId,
            byte[] payload,
            long sentTimeMs,
            int attemptCount
    )
    {
        public Entry
        {
            Objects.requireNonNull(payload, "payload");
        }

        /**
         * Returns a copy with updated sent time and incremented attempt count.
         *
         * @param newSentTimeMs new sent timestamp
         * @return updated entry
         */
        public Entry withRetransmit(long newSentTimeMs)
        {
            return new Entry(sequence, messageTypeId, payload, newSentTimeMs, attemptCount + 1);
        }
    }

    private final Map<Integer, Entry> pending;
    private final int maxSize;
    private final int maxAttempts;
    private final RttEstimator rttEstimator;

    /**
     * Creates an outbound queue with default capacity.
     *
     * @param rttEstimator RTT estimator for timeout calculation
     */
    public OutboundQueue(RttEstimator rttEstimator)
    {
        this(rttEstimator, DEFAULT_MAX_SIZE, DEFAULT_MAX_ATTEMPTS);
    }

    /**
     * Creates an outbound queue with specified capacity.
     *
     * @param rttEstimator RTT estimator for timeout calculation
     * @param maxSize      maximum number of unacknowledged messages
     * @param maxAttempts  maximum retransmission attempts
     */
    public OutboundQueue(RttEstimator rttEstimator, int maxSize, int maxAttempts)
    {
        this.rttEstimator = Objects.requireNonNull(rttEstimator, "rttEstimator");
        if (maxSize <= 0)
        {
            throw new IllegalArgumentException("maxSize must be positive: " + maxSize);
        }
        if (maxAttempts <= 0)
        {
            throw new IllegalArgumentException("maxAttempts must be positive: " + maxAttempts);
        }
        this.maxSize = maxSize;
        this.maxAttempts = maxAttempts;
        this.pending = new LinkedHashMap<>();
    }

    /**
     * Adds a message to the queue.
     *
     * @param sequence      sequence number
     * @param messageTypeId application message type
     * @param payload       serialized message data
     * @param sentTimeMs    time the message was sent
     * @return true if queued, false if queue is full (backpressure)
     */
    public boolean queue(int sequence, int messageTypeId, byte[] payload, long sentTimeMs)
    {
        Objects.requireNonNull(payload, "payload");

        if (pending.size() >= maxSize)
        {
            return false;
        }

        pending.put(sequence, new Entry(sequence, messageTypeId, payload, sentTimeMs, 1));
        return true;
    }

    /**
     * Acknowledges a single sequence number.
     *
     * @param sequence sequence number to acknowledge
     * @return the acknowledged entry, or null if not found
     */
    public Entry acknowledge(int sequence)
    {
        return pending.remove(sequence);
    }

    /**
     * Processes a cumulative acknowledgment.
     *
     * <p>Removes all messages with sequence numbers up to and including
     * the given sequence.</p>
     *
     * @param ackSequence cumulative acknowledgment sequence
     * @return number of messages acknowledged
     */
    public int acknowledgeCumulative(int ackSequence)
    {
        int count = 0;
        Iterator<Map.Entry<Integer, Entry>> iter = pending.entrySet().iterator();
        while (iter.hasNext())
        {
            Entry entry = iter.next().getValue();
            if (sequenceLessOrEqual(entry.sequence(), ackSequence))
            {
                iter.remove();
                count++;
            }
        }
        return count;
    }

    /**
     * Processes selective acknowledgments from an ack bitmap.
     *
     * <p>The bitmap covers 32 sequences after baseSeq. Bit N indicates
     * that sequence (baseSeq + 1 + N) was received.</p>
     *
     * @param baseSeq cumulative ack sequence
     * @param bitmap  selective ack bitmap
     * @return number of messages acknowledged
     */
    public int acknowledgeSelective(int baseSeq, int bitmap)
    {
        int count = acknowledgeCumulative(baseSeq);

        if (bitmap != 0)
        {
            for (int bit = 0; bit < 32; bit++)
            {
                if ((bitmap & (1 << bit)) != 0)
                {
                    int seq = baseSeq + 1 + bit;
                    if (pending.remove(seq) != null)
                    {
                        count++;
                    }
                }
            }
        }

        return count;
    }

    /**
     * Result of checking for retransmissions.
     *
     * @param retransmits entries that need retransmission
     * @param expired     entries that exceeded max attempts
     */
    public record RetransmitResult(
            List<Entry> retransmits,
            List<Entry> expired
    )
    {
    }

    /**
     * Gets messages that need retransmission.
     *
     * <p>Returns messages whose timeout has elapsed. Also identifies
     * messages that have exceeded the maximum retry attempts.</p>
     *
     * @param nowMs current time in milliseconds
     * @return retransmit result containing messages to resend and expired messages
     */
    public RetransmitResult getMessagesForRetransmit(long nowMs)
    {
        List<Entry> retransmits = new ArrayList<>();
        List<Entry> expired = new ArrayList<>();

        for (Map.Entry<Integer, Entry> mapEntry : pending.entrySet())
        {
            Entry entry = mapEntry.getValue();
            long rto = rttEstimator.calculateRtoMs(entry.attemptCount() - 1);
            long elapsed = nowMs - entry.sentTimeMs();

            if (elapsed >= rto)
            {
                if (entry.attemptCount() >= maxAttempts)
                {
                    expired.add(entry);
                }
                else
                {
                    retransmits.add(entry);
                }
            }
        }

        return new RetransmitResult(retransmits, expired);
    }

    /**
     * Marks an entry as retransmitted with updated timestamp.
     *
     * @param sequence   sequence number
     * @param sentTimeMs new sent timestamp
     */
    public void markRetransmitted(int sequence, long sentTimeMs)
    {
        Entry entry = pending.get(sequence);
        if (entry != null)
        {
            pending.put(sequence, entry.withRetransmit(sentTimeMs));
        }
    }

    /**
     * Removes an entry from the queue (e.g., due to expiry).
     *
     * @param sequence sequence number to remove
     * @return the removed entry, or null if not found
     */
    public Entry remove(int sequence)
    {
        return pending.remove(sequence);
    }

    /**
     * Returns the number of pending messages.
     *
     * @return pending message count
     */
    public int size()
    {
        return pending.size();
    }

    /**
     * Returns whether the queue is empty.
     *
     * @return true if no pending messages
     */
    public boolean isEmpty()
    {
        return pending.isEmpty();
    }

    /**
     * Returns whether the queue is full.
     *
     * @return true if at capacity
     */
    public boolean isFull()
    {
        return pending.size() >= maxSize;
    }

    /**
     * Returns the maximum queue size.
     *
     * @return maximum capacity
     */
    public int getMaxSize()
    {
        return maxSize;
    }

    /**
     * Compares sequence numbers accounting for wraparound.
     *
     * @param seq1 first sequence
     * @param seq2 second sequence
     * @return true if seq1 <= seq2 accounting for wraparound
     */
    private static boolean sequenceLessOrEqual(int seq1, int seq2)
    {
        return ((seq1 - seq2) & 0x80000000) != 0 || seq1 == seq2;
    }
}
