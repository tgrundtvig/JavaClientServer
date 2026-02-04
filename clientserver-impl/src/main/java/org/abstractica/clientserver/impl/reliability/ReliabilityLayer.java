package org.abstractica.clientserver.impl.reliability;

import org.abstractica.clientserver.impl.protocol.Ack;
import org.abstractica.clientserver.impl.protocol.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Coordinates reliable message delivery over an unreliable transport.
 *
 * <p>Provides:</p>
 * <ul>
 *   <li>Sequence numbering for reliable messages</li>
 *   <li>Acknowledgment generation and processing</li>
 *   <li>Retransmission on timeout</li>
 *   <li>In-order delivery to the application</li>
 * </ul>
 */
public class ReliabilityLayer
{
    /**
     * Result of receiving a data packet.
     *
     * @param deliverableMessages messages ready for application delivery
     * @param isDuplicate        whether the packet was a duplicate
     */
    public record ReceiveResult(
            List<ReceivedMessage> deliverableMessages,
            boolean isDuplicate
    )
    {
        public ReceiveResult
        {
            Objects.requireNonNull(deliverableMessages, "deliverableMessages");
        }

        /**
         * Creates an empty result (no deliverable messages, not a duplicate).
         */
        public static ReceiveResult empty()
        {
            return new ReceiveResult(List.of(), false);
        }

        /**
         * Creates a duplicate result.
         */
        public static ReceiveResult duplicate()
        {
            return new ReceiveResult(List.of(), true);
        }
    }

    /**
     * A message received and ready for delivery.
     *
     * @param messageTypeId application message type
     * @param payload       serialized message data
     */
    public record ReceivedMessage(
            int messageTypeId,
            byte[] payload
    )
    {
        public ReceivedMessage
        {
            Objects.requireNonNull(payload, "payload");
        }
    }

    /**
     * Result of a tick operation.
     *
     * @param retransmits packets to retransmit
     * @param expired     sequences that exceeded max retries
     */
    public record TickResult(
            List<Data> retransmits,
            List<Integer> expired
    )
    {
        public TickResult
        {
            Objects.requireNonNull(retransmits, "retransmits");
            Objects.requireNonNull(expired, "expired");
        }
    }

    private final RttEstimator rttEstimator;
    private final OutboundQueue outboundQueue;
    private final InboundBuffer inboundBuffer;

    private int nextOutboundSequence;
    private boolean ackPending;
    private long lastAckTimeMs;

    /**
     * Minimum interval between standalone acks in milliseconds.
     */
    private static final long ACK_DELAY_MS = 10;

    /**
     * Creates a reliability layer with default settings.
     */
    public ReliabilityLayer()
    {
        this(new RttEstimator());
    }

    /**
     * Creates a reliability layer with the specified RTT estimator.
     *
     * @param rttEstimator RTT estimator for timeout calculation
     */
    public ReliabilityLayer(RttEstimator rttEstimator)
    {
        this(
                rttEstimator,
                new OutboundQueue(rttEstimator),
                new InboundBuffer()
        );
    }

    /**
     * Creates a reliability layer with specified components.
     *
     * @param rttEstimator  RTT estimator
     * @param outboundQueue outbound message queue
     * @param inboundBuffer inbound message buffer
     */
    public ReliabilityLayer(
            RttEstimator rttEstimator,
            OutboundQueue outboundQueue,
            InboundBuffer inboundBuffer
    )
    {
        this.rttEstimator = Objects.requireNonNull(rttEstimator, "rttEstimator");
        this.outboundQueue = Objects.requireNonNull(outboundQueue, "outboundQueue");
        this.inboundBuffer = Objects.requireNonNull(inboundBuffer, "inboundBuffer");
        this.nextOutboundSequence = 0;
        this.ackPending = false;
        this.lastAckTimeMs = 0;
    }

    /**
     * Creates a reliable Data packet and queues it for acknowledgment tracking.
     *
     * @param messageTypeId application message type
     * @param payload       serialized message data
     * @param nowMs         current time in milliseconds
     * @return the Data packet to send, or empty if backpressure (queue full)
     */
    public Optional<Data> sendReliable(int messageTypeId, byte[] payload, long nowMs)
    {
        Objects.requireNonNull(payload, "payload");

        int sequence = nextOutboundSequence;

        if (!outboundQueue.queue(sequence, messageTypeId, payload, nowMs))
        {
            return Optional.empty();
        }

        nextOutboundSequence++;

        Data packet = Data.reliable(sequence, messageTypeId, payload);

        // Piggyback ack if pending
        if (ackPending)
        {
            packet = addPiggybackAck(packet);
            ackPending = false;
            lastAckTimeMs = nowMs;
        }

        return Optional.of(packet);
    }

    /**
     * Creates an unreliable Data packet.
     *
     * <p>Unreliable messages are not sequenced or acknowledged.</p>
     *
     * @param messageTypeId application message type
     * @param payload       serialized message data
     * @param nowMs         current time in milliseconds
     * @return the Data packet to send
     */
    public Data sendUnreliable(int messageTypeId, byte[] payload, long nowMs)
    {
        Objects.requireNonNull(payload, "payload");

        Data packet = Data.unreliable(messageTypeId, payload);

        // Piggyback ack if pending
        if (ackPending)
        {
            packet = addPiggybackAck(packet);
            ackPending = false;
            lastAckTimeMs = nowMs;
        }

        return packet;
    }

    /**
     * Processes a received Data packet.
     *
     * @param packet the received packet
     * @param nowMs  current time in milliseconds
     * @return result containing deliverable messages
     */
    public ReceiveResult receive(Data packet, long nowMs)
    {
        Objects.requireNonNull(packet, "packet");

        // Process piggybacked ack if present
        packet.ackSequence().ifPresent(ackSeq ->
        {
            // Simple ack - no bitmap in piggybacked acks
            processAck(ackSeq, 0, nowMs);
        });

        // Handle unreliable messages - deliver immediately
        if (!packet.reliable())
        {
            ReceivedMessage message = new ReceivedMessage(
                    packet.messageTypeId(),
                    packet.payload()
            );
            return new ReceiveResult(List.of(message), false);
        }

        // Reliable message - buffer and deliver in order
        int sequence = packet.sequence().orElseThrow();

        InboundBuffer.ReceiveResult result = inboundBuffer.receive(
                sequence,
                packet.messageTypeId(),
                packet.payload()
        );

        if (result == InboundBuffer.ReceiveResult.DUPLICATE ||
                result == InboundBuffer.ReceiveResult.TOO_OLD)
        {
            ackPending = true;
            return ReceiveResult.duplicate();
        }

        if (result == InboundBuffer.ReceiveResult.BUFFER_FULL)
        {
            // Drop the message but still ack what we have
            ackPending = true;
            return ReceiveResult.empty();
        }

        // Message accepted - schedule ack
        ackPending = true;

        // Collect deliverable messages
        List<ReceivedMessage> deliverable = new ArrayList<>();
        InboundBuffer.Entry entry;
        while ((entry = inboundBuffer.getNextDeliverable()) != null)
        {
            deliverable.add(new ReceivedMessage(entry.messageTypeId(), entry.payload()));
        }

        return new ReceiveResult(deliverable, false);
    }

    /**
     * Processes a received standalone Ack packet.
     *
     * @param packet the received ack
     * @param nowMs  current time in milliseconds
     * @return number of messages acknowledged
     */
    public int receive(Ack packet, long nowMs)
    {
        Objects.requireNonNull(packet, "packet");
        return processAck(packet.ackSequence(), packet.ackBitmap(), nowMs);
    }

    /**
     * Performs periodic maintenance and returns packets to retransmit.
     *
     * @param nowMs current time in milliseconds
     * @return tick result with retransmits and expired sequences
     */
    public TickResult tick(long nowMs)
    {
        OutboundQueue.RetransmitResult result = outboundQueue.getMessagesForRetransmit(nowMs);

        List<Data> retransmits = new ArrayList<>();
        List<Integer> expired = new ArrayList<>();

        // Build retransmit packets
        for (OutboundQueue.Entry entry : result.retransmits())
        {
            Data packet = Data.reliable(entry.sequence(), entry.messageTypeId(), entry.payload());

            // Piggyback ack on retransmits too
            if (ackPending)
            {
                packet = addPiggybackAck(packet);
                ackPending = false;
                lastAckTimeMs = nowMs;
            }

            retransmits.add(packet);
            outboundQueue.markRetransmitted(entry.sequence(), nowMs);
        }

        // Collect expired sequences
        for (OutboundQueue.Entry entry : result.expired())
        {
            expired.add(entry.sequence());
            outboundQueue.remove(entry.sequence());
        }

        return new TickResult(retransmits, expired);
    }

    /**
     * Gets a standalone Ack packet for the current receive state.
     *
     * <p>Call this when you need to send an ack without data to piggyback on.</p>
     *
     * @param nowMs current time in milliseconds
     * @return ack packet, or empty if no ack is needed
     */
    public Optional<Ack> getAckToSend(long nowMs)
    {
        if (!ackPending)
        {
            return Optional.empty();
        }

        // Check delay to avoid flooding acks
        if (nowMs - lastAckTimeMs < ACK_DELAY_MS)
        {
            return Optional.empty();
        }

        int ackSeq = inboundBuffer.getHighestConsecutiveReceived();
        if (ackSeq < 0)
        {
            return Optional.empty();
        }

        ackPending = false;
        lastAckTimeMs = nowMs;

        return Optional.of(new Ack(ackSeq, inboundBuffer.getReceivedBitmap()));
    }

    /**
     * Updates RTT estimate from a measured sample.
     *
     * @param rttMs measured round-trip time in milliseconds
     */
    public void updateRtt(long rttMs)
    {
        rttEstimator.update(rttMs);
    }

    /**
     * Returns whether there are pending outbound messages awaiting ack.
     *
     * @return true if outbound queue is not empty
     */
    public boolean hasPendingOutbound()
    {
        return !outboundQueue.isEmpty();
    }

    /**
     * Returns whether the outbound queue is full (backpressure).
     *
     * @return true if queue is at capacity
     */
    public boolean isBackpressured()
    {
        return outboundQueue.isFull();
    }

    /**
     * Returns the number of pending outbound messages.
     *
     * @return pending message count
     */
    public int getPendingOutboundCount()
    {
        return outboundQueue.size();
    }

    /**
     * Returns whether an ack needs to be sent.
     *
     * @return true if ack is pending
     */
    public boolean isAckPending()
    {
        return ackPending;
    }

    /**
     * Forces an ack to be scheduled.
     */
    public void scheduleAck()
    {
        this.ackPending = true;
    }

    /**
     * Returns the RTT estimator.
     *
     * @return RTT estimator
     */
    public RttEstimator getRttEstimator()
    {
        return rttEstimator;
    }

    /**
     * Processes an ack and updates RTT if applicable.
     */
    private int processAck(int ackSequence, int bitmap, long nowMs)
    {
        // Note: RTT updates should be done by the caller when they have
        // the original send time information
        return outboundQueue.acknowledgeSelective(ackSequence, bitmap);
    }

    /**
     * Adds a piggybacked ack to a Data packet.
     */
    private Data addPiggybackAck(Data packet)
    {
        int ackSeq = inboundBuffer.getHighestConsecutiveReceived();
        if (ackSeq >= 0)
        {
            return packet.withAck(ackSeq);
        }
        return packet;
    }
}
