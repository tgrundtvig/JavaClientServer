package org.abstractica.clientserver;

/**
 * Message delivery mode.
 */
public enum Delivery
{
    /**
     * Message is delivered exactly once, in order.
     * Retransmitted until acknowledged.
     */
    RELIABLE,

    /**
     * Message may be lost, duplicated, or reordered.
     * No retransmission, lowest latency.
     */
    UNRELIABLE
}
