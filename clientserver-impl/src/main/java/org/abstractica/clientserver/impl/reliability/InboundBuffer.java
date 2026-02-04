package org.abstractica.clientserver.impl.reliability;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Buffers out-of-order messages for in-order delivery.
 *
 * <p>Tracks received sequences, detects duplicates, and delivers
 * messages to the application in sequence order.</p>
 */
public class InboundBuffer
{
    /**
     * Default maximum number of buffered out-of-order messages.
     */
    public static final int DEFAULT_MAX_SIZE = 256;

    /**
     * A received message entry.
     *
     * @param sequence      sequence number
     * @param messageTypeId application message type
     * @param payload       serialized message data
     */
    public record Entry(
            int sequence,
            int messageTypeId,
            byte[] payload
    )
    {
        public Entry
        {
            Objects.requireNonNull(payload, "payload");
        }
    }

    private final Map<Integer, Entry> buffer;
    private final Set<Integer> recentlyDelivered;
    private final int maxSize;
    private int nextExpectedSequence;
    private int highestConsecutiveReceived;
    private int receivedBitmap;
    private boolean firstMessageReceived;

    /**
     * Creates an inbound buffer with default capacity.
     */
    public InboundBuffer()
    {
        this(DEFAULT_MAX_SIZE);
    }

    /**
     * Creates an inbound buffer with specified capacity.
     *
     * @param maxSize maximum number of buffered messages
     */
    public InboundBuffer(int maxSize)
    {
        if (maxSize <= 0)
        {
            throw new IllegalArgumentException("maxSize must be positive: " + maxSize);
        }
        this.maxSize = maxSize;
        this.buffer = new HashMap<>();
        this.recentlyDelivered = new HashSet<>();
        this.nextExpectedSequence = 0;
        this.highestConsecutiveReceived = -1;
        this.receivedBitmap = 0;
        this.firstMessageReceived = false;
    }

    /**
     * Result of receiving a message.
     */
    public enum ReceiveResult
    {
        /** Message accepted and buffered or ready for delivery */
        ACCEPTED,
        /** Message was a duplicate */
        DUPLICATE,
        /** Buffer is full, message dropped */
        BUFFER_FULL,
        /** Sequence is too old (already passed) */
        TOO_OLD
    }

    /**
     * Receives a message and buffers it if out of order.
     *
     * @param sequence      sequence number
     * @param messageTypeId application message type
     * @param payload       serialized message data
     * @return result indicating whether message was accepted
     */
    public ReceiveResult receive(int sequence, int messageTypeId, byte[] payload)
    {
        Objects.requireNonNull(payload, "payload");

        if (!firstMessageReceived)
        {
            firstMessageReceived = true;
            // Keep nextExpectedSequence at its initialized value (0 or from reset)
        }

        // Check if this is a duplicate
        if (buffer.containsKey(sequence))
        {
            return ReceiveResult.DUPLICATE;
        }

        // Check if already delivered (in recently delivered set or before next expected)
        if (recentlyDelivered.contains(sequence))
        {
            return ReceiveResult.DUPLICATE;
        }

        // Check if sequence is before what we expect (already passed)
        if (sequenceBefore(sequence, nextExpectedSequence))
        {
            return ReceiveResult.TOO_OLD;
        }

        // Check buffer capacity (only for out-of-order messages)
        if (sequence != nextExpectedSequence && buffer.size() >= maxSize)
        {
            return ReceiveResult.BUFFER_FULL;
        }

        // Accept the message
        buffer.put(sequence, new Entry(sequence, messageTypeId, payload));
        updateAckState(sequence);

        return ReceiveResult.ACCEPTED;
    }

    /**
     * Gets the next deliverable message in sequence order.
     *
     * @return next message, or null if none available
     */
    public Entry getNextDeliverable()
    {
        Entry entry = buffer.remove(nextExpectedSequence);
        if (entry != null)
        {
            // Track recently delivered to detect late duplicates
            trackDelivered(nextExpectedSequence);
            nextExpectedSequence++;
        }
        return entry;
    }

    /**
     * Returns the highest consecutive sequence received.
     *
     * <p>This is used for cumulative acknowledgments.</p>
     *
     * @return highest consecutive sequence, or -1 if none received
     */
    public int getHighestConsecutiveReceived()
    {
        return highestConsecutiveReceived;
    }

    /**
     * Returns the selective ack bitmap.
     *
     * <p>Covers 32 sequences after the highest consecutive received.
     * Bit N indicates that sequence (highestConsecutive + 1 + N) was received.</p>
     *
     * @return selective ack bitmap
     */
    public int getReceivedBitmap()
    {
        return receivedBitmap;
    }

    /**
     * Returns the next expected sequence number.
     *
     * @return next expected sequence
     */
    public int getNextExpectedSequence()
    {
        return nextExpectedSequence;
    }

    /**
     * Returns the number of buffered out-of-order messages.
     *
     * @return buffer size
     */
    public int size()
    {
        return buffer.size();
    }

    /**
     * Returns whether there are buffered messages.
     *
     * @return true if buffer is not empty
     */
    public boolean hasBufferedMessages()
    {
        return !buffer.isEmpty();
    }

    /**
     * Returns whether the next message is ready for delivery.
     *
     * @return true if next expected sequence is buffered
     */
    public boolean hasDeliverable()
    {
        return buffer.containsKey(nextExpectedSequence);
    }

    /**
     * Resets the buffer state.
     *
     * @param initialSequence the sequence number to expect next
     */
    public void reset(int initialSequence)
    {
        buffer.clear();
        recentlyDelivered.clear();
        nextExpectedSequence = initialSequence;
        highestConsecutiveReceived = initialSequence - 1;
        receivedBitmap = 0;
        firstMessageReceived = true;
    }

    /**
     * Updates ack state when a sequence is received.
     */
    private void updateAckState(int sequence)
    {
        if (!firstMessageReceived || highestConsecutiveReceived < 0)
        {
            if (sequence == nextExpectedSequence)
            {
                highestConsecutiveReceived = sequence;
                advanceConsecutive();
            }
            else
            {
                highestConsecutiveReceived = nextExpectedSequence - 1;
                updateBitmap(sequence);
            }
            return;
        }

        // Check if this sequence advances the consecutive range
        if (sequence == highestConsecutiveReceived + 1)
        {
            highestConsecutiveReceived = sequence;
            advanceConsecutive();
        }
        else if (sequenceAfter(sequence, highestConsecutiveReceived))
        {
            // Out of order - update bitmap
            updateBitmap(sequence);
        }
    }

    /**
     * Advances the consecutive received counter based on buffered messages.
     */
    private void advanceConsecutive()
    {
        while (buffer.containsKey(highestConsecutiveReceived + 1))
        {
            highestConsecutiveReceived++;
        }
        rebuildBitmap();
    }

    /**
     * Updates the bitmap for a received sequence.
     */
    private void updateBitmap(int sequence)
    {
        int offset = sequence - highestConsecutiveReceived - 1;
        if (offset >= 0 && offset < 32)
        {
            receivedBitmap |= (1 << offset);
        }
    }

    /**
     * Rebuilds the bitmap after advancing consecutive counter.
     */
    private void rebuildBitmap()
    {
        receivedBitmap = 0;
        for (int seq : buffer.keySet())
        {
            int offset = seq - highestConsecutiveReceived - 1;
            if (offset >= 0 && offset < 32)
            {
                receivedBitmap |= (1 << offset);
            }
        }
    }

    /**
     * Tracks a delivered sequence for duplicate detection.
     */
    private void trackDelivered(int sequence)
    {
        recentlyDelivered.add(sequence);

        // Limit the size of recently delivered set
        if (recentlyDelivered.size() > maxSize * 2)
        {
            // Remove old entries (before nextExpected - maxSize)
            int threshold = nextExpectedSequence - maxSize;
            recentlyDelivered.removeIf(seq -> sequenceBefore(seq, threshold));
        }
    }

    /**
     * Checks if seq1 comes before seq2 accounting for wraparound.
     */
    private static boolean sequenceBefore(int seq1, int seq2)
    {
        return ((seq1 - seq2) & 0x80000000) != 0;
    }

    /**
     * Checks if seq1 comes after seq2 accounting for wraparound.
     */
    private static boolean sequenceAfter(int seq1, int seq2)
    {
        return sequenceBefore(seq2, seq1) && seq1 != seq2;
    }
}
