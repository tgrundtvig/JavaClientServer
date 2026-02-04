package org.abstractica.clientserver.impl.protocol;

/**
 * Standalone acknowledgment packet (sent encrypted).
 *
 * <p>Wire format:</p>
 * <pre>
 * [type: 1 byte = 0x21]
 * [ackSequence: 4 bytes]
 * [ackBitmap: 4 bytes]
 * </pre>
 *
 * <p>The ackBitmap provides selective acknowledgment for sequences beyond ackSequence.
 * Bit N indicates that sequence (ackSequence + 1 + N) was received.</p>
 *
 * @param ackSequence highest consecutive sequence number received
 * @param ackBitmap   bitmap for 32 sequences after ackSequence
 */
public record Ack(
        int ackSequence,
        int ackBitmap
)
{
    /**
     * Creates an Ack for a single sequence with no selective acks.
     *
     * @param sequence the sequence to acknowledge
     * @return simple Ack packet
     */
    public static Ack single(int sequence)
    {
        return new Ack(sequence, 0);
    }

    /**
     * Checks if a specific sequence is acknowledged by this Ack.
     *
     * @param sequence the sequence to check
     * @return true if acknowledged
     */
    public boolean acknowledges(int sequence)
    {
        if (sequence <= ackSequence)
        {
            return true;
        }
        int offset = sequence - ackSequence - 1;
        if (offset < 0 || offset >= 32)
        {
            return false;
        }
        return (ackBitmap & (1 << offset)) != 0;
    }

    /**
     * Returns a new Ack with an additional sequence marked in the bitmap.
     *
     * @param sequence the sequence to add
     * @return new Ack with updated bitmap
     */
    public Ack withSelectiveAck(int sequence)
    {
        int offset = sequence - ackSequence - 1;
        if (offset < 0 || offset >= 32)
        {
            return this;
        }
        return new Ack(ackSequence, ackBitmap | (1 << offset));
    }
}
