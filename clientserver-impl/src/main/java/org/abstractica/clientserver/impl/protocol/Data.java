package org.abstractica.clientserver.impl.protocol;

import java.util.Objects;
import java.util.Optional;

/**
 * Application data packet (sent encrypted).
 *
 * <p>Wire format:</p>
 * <pre>
 * [type: 1 byte = 0x20]
 * [flags: 1 byte]
 * [sequence: 4 bytes] (if RELIABLE flag set)
 * [ackSequence: 4 bytes] (if HAS_ACK flag set)
 * [messageTypeId: 2 bytes]
 * [payload: variable]
 * </pre>
 *
 * @param reliable      whether this message requires acknowledgment
 * @param sequence      sequence number (present if reliable)
 * @param ackSequence   piggybacked ack (present if acknowledging)
 * @param messageTypeId application message type identifier
 * @param payload       serialized application message
 */
public record Data(
        boolean reliable,
        Optional<Integer> sequence,
        Optional<Integer> ackSequence,
        int messageTypeId,
        byte[] payload
)
{
    /**
     * Flag bit: message is reliable and has sequence number.
     */
    public static final int FLAG_RELIABLE = 0x01;

    /**
     * Flag bit: packet includes piggybacked acknowledgment.
     */
    public static final int FLAG_HAS_ACK = 0x02;

    public Data
    {
        Objects.requireNonNull(sequence, "sequence");
        Objects.requireNonNull(ackSequence, "ackSequence");
        Objects.requireNonNull(payload, "payload");

        if (reliable && sequence.isEmpty())
        {
            throw new IllegalArgumentException("Reliable messages must have a sequence number");
        }
        if (!reliable && sequence.isPresent())
        {
            throw new IllegalArgumentException("Unreliable messages must not have a sequence number");
        }
        if (messageTypeId < 0 || messageTypeId > 0xFFFF)
        {
            throw new IllegalArgumentException("Message type ID must be 0-65535: " + messageTypeId);
        }
    }

    /**
     * Computes the flags byte for this packet.
     *
     * @return flags byte
     */
    public int getFlags()
    {
        int flags = 0;
        if (reliable)
        {
            flags |= FLAG_RELIABLE;
        }
        if (ackSequence.isPresent())
        {
            flags |= FLAG_HAS_ACK;
        }
        return flags;
    }

    /**
     * Creates an unreliable data packet.
     *
     * @param messageTypeId the message type ID
     * @param payload       the serialized message
     * @return unreliable Data packet
     */
    public static Data unreliable(int messageTypeId, byte[] payload)
    {
        return new Data(false, Optional.empty(), Optional.empty(), messageTypeId, payload);
    }

    /**
     * Creates a reliable data packet.
     *
     * @param sequence      the sequence number
     * @param messageTypeId the message type ID
     * @param payload       the serialized message
     * @return reliable Data packet
     */
    public static Data reliable(int sequence, int messageTypeId, byte[] payload)
    {
        return new Data(true, Optional.of(sequence), Optional.empty(), messageTypeId, payload);
    }

    /**
     * Returns a copy of this packet with a piggybacked ack.
     *
     * @param ackSeq the sequence number to acknowledge
     * @return new Data packet with ack
     */
    public Data withAck(int ackSeq)
    {
        return new Data(reliable, sequence, Optional.of(ackSeq), messageTypeId, payload);
    }
}
