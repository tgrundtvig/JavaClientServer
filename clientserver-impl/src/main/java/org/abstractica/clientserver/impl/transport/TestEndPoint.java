package org.abstractica.clientserver.impl.transport;

import java.time.Duration;

/**
 * An endpoint with testing capabilities for simulating network conditions.
 *
 * <p>TestEndPoint extends {@link EndPoint} with methods for simulating
 * packet loss, latency, jitter, and reordering. It also provides statistics
 * for verifying test behavior.</p>
 *
 * <p>Use {@link SimulatedNetwork} to create TestEndPoint instances:</p>
 * <pre>{@code
 * SimulatedNetwork network = new SimulatedNetwork();
 * TestEndPoint server = network.createTestEndPoint(serverAddress);
 * TestEndPoint client = network.createTestEndPoint();
 *
 * // Configure network conditions
 * client.setPacketLossRate(0.1);
 * server.setLatency(Duration.ofMillis(50));
 *
 * // One-shot packet manipulation
 * client.dropNextPacket();
 * }</pre>
 */
public interface TestEndPoint extends EndPoint
{
    // ========== Network Condition Simulation ==========

    /**
     * Sets the packet loss rate.
     *
     * @param rate the probability of dropping a packet (0.0 to 1.0)
     */
    void setPacketLossRate(double rate);

    /**
     * Sets fixed latency for packet delivery.
     *
     * @param latency the latency to apply to all packets
     */
    void setLatency(Duration latency);

    /**
     * Sets the latency range for packet delivery.
     *
     * <p>Each packet will be delayed by a random duration between min and max.</p>
     *
     * @param min minimum latency
     * @param max maximum latency
     */
    void setLatency(Duration min, Duration max);

    /**
     * Sets the packet reordering rate.
     *
     * <p>When a packet is selected for reordering, it will be delayed
     * by an additional amount to arrive after subsequent packets.</p>
     *
     * @param rate the probability of reordering a packet (0.0 to 1.0)
     */
    void setReorderRate(double rate);

    /**
     * Sets the additional delay applied to reordered packets.
     *
     * @param delay the extra delay for reordered packets
     */
    void setReorderDelay(Duration delay);

    // ========== One-Shot Packet Manipulation ==========

    /**
     * Causes the next outgoing packet to be dropped.
     *
     * <p>This is a one-shot operation that affects only the next packet.</p>
     */
    void dropNextPacket();

    /**
     * Causes the next outgoing packet to be delayed by the specified duration.
     *
     * <p>This is a one-shot operation that affects only the next packet.
     * The delay is added on top of any configured latency.</p>
     *
     * @param delay the additional delay for the next packet
     */
    void delayNextPacket(Duration delay);

    /**
     * Resets all network condition settings to defaults (no loss, no latency).
     */
    void reset();

    // ========== Statistics ==========

    /**
     * Returns the number of packets sent through this endpoint.
     *
     * @return packets sent count
     */
    int getPacketsSent();

    /**
     * Returns the number of packets dropped (due to simulated loss).
     *
     * @return packets dropped count
     */
    int getPacketsDropped();

    /**
     * Returns the number of packets successfully delivered.
     *
     * @return packets delivered count
     */
    int getPacketsDelivered();

    /**
     * Resets all statistics counters to zero.
     */
    void resetStats();
}
