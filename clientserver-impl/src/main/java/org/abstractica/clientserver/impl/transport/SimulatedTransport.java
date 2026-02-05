package org.abstractica.clientserver.impl.transport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.Delayed;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

/**
 * Simulated transport for testing network conditions.
 *
 * <p>This transport allows simulation of packet loss, latency, jitter,
 * and packet reordering for testing reliability and reconnection logic.</p>
 *
 * <p>Two SimulatedTransports can be connected together to simulate
 * a network link between client and server without actual UDP sockets.</p>
 */
public class SimulatedTransport implements Transport
{
    private static final Logger LOG = LoggerFactory.getLogger(SimulatedTransport.class);
    private static final AtomicInteger PORT_COUNTER = new AtomicInteger(50000);

    private final SocketAddress localAddress;
    private final Random random;
    private final DelayQueue<DelayedPacket> deliveryQueue;
    private final BlockingQueue<Runnable> pendingDeliveries;

    private BiConsumer<ByteBuffer, SocketAddress> receiveHandler;
    private SimulatedTransport peer;
    private Thread deliveryThread;
    private final AtomicBoolean running = new AtomicBoolean(false);

    // Network condition simulation
    private volatile double packetLossRate = 0.0;
    private volatile Duration minLatency = Duration.ZERO;
    private volatile Duration maxLatency = Duration.ZERO;
    private volatile double reorderRate = 0.0;
    private volatile Duration reorderDelay = Duration.ofMillis(50);

    // Per-packet manipulation
    private volatile boolean dropNextPacket = false;
    private volatile Duration delayNextPacket = null;

    // Statistics
    private final AtomicInteger packetsSent = new AtomicInteger(0);
    private final AtomicInteger packetsDropped = new AtomicInteger(0);
    private final AtomicInteger packetsDelivered = new AtomicInteger(0);

    /**
     * Creates a simulated transport with an auto-assigned local address.
     */
    public SimulatedTransport()
    {
        this(new InetSocketAddress("127.0.0.1", PORT_COUNTER.getAndIncrement()));
    }

    /**
     * Creates a simulated transport with the specified local address.
     *
     * @param localAddress the local address for this transport
     */
    public SimulatedTransport(SocketAddress localAddress)
    {
        this.localAddress = Objects.requireNonNull(localAddress, "localAddress");
        this.random = new Random();
        this.deliveryQueue = new DelayQueue<>();
        this.pendingDeliveries = new LinkedBlockingQueue<>();
    }

    // ========== Configuration ==========

    /**
     * Connects this transport to a peer transport.
     *
     * <p>Packets sent from this transport will be delivered to the peer,
     * and vice versa. Both transports must be connected to each other.</p>
     *
     * @param peer the peer transport
     */
    public void connectTo(SimulatedTransport peer)
    {
        this.peer = Objects.requireNonNull(peer, "peer");
    }

    /**
     * Sets the packet loss rate.
     *
     * @param rate the probability of dropping a packet (0.0 to 1.0)
     */
    public void setPacketLossRate(double rate)
    {
        if (rate < 0.0 || rate > 1.0)
        {
            throw new IllegalArgumentException("Rate must be 0.0-1.0: " + rate);
        }
        this.packetLossRate = rate;
    }

    /**
     * Sets the latency range for packet delivery.
     *
     * <p>Each packet will be delayed by a random duration between min and max.</p>
     *
     * @param min minimum latency
     * @param max maximum latency
     */
    public void setLatency(Duration min, Duration max)
    {
        Objects.requireNonNull(min, "min");
        Objects.requireNonNull(max, "max");
        if (min.isNegative() || max.isNegative())
        {
            throw new IllegalArgumentException("Latency must be non-negative");
        }
        if (min.compareTo(max) > 0)
        {
            throw new IllegalArgumentException("min must be <= max");
        }
        this.minLatency = min;
        this.maxLatency = max;
    }

    /**
     * Sets fixed latency for packet delivery.
     *
     * @param latency the latency to apply to all packets
     */
    public void setLatency(Duration latency)
    {
        setLatency(latency, latency);
    }

    /**
     * Sets the packet reordering rate.
     *
     * <p>When a packet is selected for reordering, it will be delayed
     * by an additional amount to arrive after subsequent packets.</p>
     *
     * @param rate the probability of reordering a packet (0.0 to 1.0)
     */
    public void setReorderRate(double rate)
    {
        if (rate < 0.0 || rate > 1.0)
        {
            throw new IllegalArgumentException("Rate must be 0.0-1.0: " + rate);
        }
        this.reorderRate = rate;
    }

    /**
     * Sets the additional delay applied to reordered packets.
     *
     * @param delay the extra delay for reordered packets
     */
    public void setReorderDelay(Duration delay)
    {
        this.reorderDelay = Objects.requireNonNull(delay, "delay");
    }

    /**
     * Causes the next packet to be dropped.
     *
     * <p>This is a one-shot operation that affects only the next packet.</p>
     */
    public void dropNextPacket()
    {
        this.dropNextPacket = true;
    }

    /**
     * Causes the next packet to be delayed by the specified duration.
     *
     * <p>This is a one-shot operation that affects only the next packet.
     * The delay is added on top of any configured latency.</p>
     *
     * @param delay the additional delay for the next packet
     */
    public void delayNextPacket(Duration delay)
    {
        this.delayNextPacket = Objects.requireNonNull(delay, "delay");
    }

    /**
     * Resets all network condition settings to defaults (no loss, no latency).
     */
    public void reset()
    {
        this.packetLossRate = 0.0;
        this.minLatency = Duration.ZERO;
        this.maxLatency = Duration.ZERO;
        this.reorderRate = 0.0;
        this.dropNextPacket = false;
        this.delayNextPacket = null;
    }

    // ========== Statistics ==========

    /**
     * Returns the number of packets sent through this transport.
     */
    public int getPacketsSent()
    {
        return packetsSent.get();
    }

    /**
     * Returns the number of packets dropped (due to simulated loss).
     */
    public int getPacketsDropped()
    {
        return packetsDropped.get();
    }

    /**
     * Returns the number of packets successfully delivered.
     */
    public int getPacketsDelivered()
    {
        return packetsDelivered.get();
    }

    /**
     * Resets all statistics counters to zero.
     */
    public void resetStats()
    {
        packetsSent.set(0);
        packetsDropped.set(0);
        packetsDelivered.set(0);
    }

    // ========== Transport Interface ==========

    @Override
    public void setReceiveHandler(BiConsumer<ByteBuffer, SocketAddress> handler)
    {
        this.receiveHandler = Objects.requireNonNull(handler, "handler");
    }

    @Override
    public void start()
    {
        if (!running.compareAndSet(false, true))
        {
            throw new IllegalStateException("Transport already started");
        }

        if (receiveHandler == null)
        {
            throw new IllegalStateException("Receive handler must be set before starting");
        }

        // Start delivery thread for delayed packets
        deliveryThread = new Thread(this::deliveryLoop, "simulated-transport-" + localAddress);
        deliveryThread.setDaemon(true);
        deliveryThread.start();

        LOG.info("Simulated transport started on {}", localAddress);
    }

    @Override
    public void send(ByteBuffer data, SocketAddress destination)
    {
        if (!running.get())
        {
            throw new IllegalStateException("Transport not running");
        }

        if (peer == null)
        {
            LOG.warn("No peer connected, dropping packet");
            return;
        }

        packetsSent.incrementAndGet();

        // Check one-shot drop
        if (dropNextPacket)
        {
            dropNextPacket = false;
            packetsDropped.incrementAndGet();
            LOG.debug("Dropped packet (one-shot)");
            return;
        }

        // Check random packet loss
        if (packetLossRate > 0.0 && random.nextDouble() < packetLossRate)
        {
            packetsDropped.incrementAndGet();
            LOG.debug("Dropped packet (random loss)");
            return;
        }

        // Copy data for async delivery
        byte[] copy = new byte[data.remaining()];
        data.get(copy);

        // Calculate delivery delay
        long delayMs = calculateDelay();

        // Check one-shot delay
        if (delayNextPacket != null)
        {
            delayMs += delayNextPacket.toMillis();
            delayNextPacket = null;
        }

        // Check reordering
        if (reorderRate > 0.0 && random.nextDouble() < reorderRate)
        {
            delayMs += reorderDelay.toMillis();
            LOG.debug("Reordering packet with extra {} ms delay", reorderDelay.toMillis());
        }

        // Schedule delivery
        long deliveryTime = System.currentTimeMillis() + delayMs;
        peer.scheduleDelivery(copy, localAddress, deliveryTime);
    }

    @Override
    public void close()
    {
        if (!running.compareAndSet(true, false))
        {
            return;
        }

        LOG.info("Closing simulated transport");

        if (deliveryThread != null)
        {
            deliveryThread.interrupt();
            try
            {
                deliveryThread.join(1000);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
            }
        }

        deliveryQueue.clear();
    }

    @Override
    public SocketAddress getLocalAddress()
    {
        return localAddress;
    }

    // ========== Internal ==========

    private long calculateDelay()
    {
        if (minLatency.equals(maxLatency))
        {
            return minLatency.toMillis();
        }

        long min = minLatency.toMillis();
        long max = maxLatency.toMillis();
        return min + (long) (random.nextDouble() * (max - min));
    }

    private void scheduleDelivery(byte[] data, SocketAddress source, long deliveryTime)
    {
        deliveryQueue.put(new DelayedPacket(data, source, deliveryTime));
    }

    private void deliveryLoop()
    {
        LOG.debug("Delivery loop started");

        while (running.get())
        {
            try
            {
                DelayedPacket packet = deliveryQueue.poll(100, TimeUnit.MILLISECONDS);
                if (packet != null)
                {
                    deliverPacket(packet);
                }
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                break;
            }
            catch (Exception e)
            {
                LOG.error("Error in delivery loop", e);
            }
        }

        LOG.debug("Delivery loop stopped");
    }

    private void deliverPacket(DelayedPacket packet)
    {
        if (receiveHandler == null)
        {
            return;
        }

        packetsDelivered.incrementAndGet();

        ByteBuffer buffer = ByteBuffer.wrap(packet.data);
        try
        {
            receiveHandler.accept(buffer, packet.source);
        }
        catch (Exception e)
        {
            LOG.error("Error in receive handler", e);
        }
    }

    /**
     * A packet scheduled for delayed delivery.
     */
    private record DelayedPacket(byte[] data, SocketAddress source, long deliveryTime) implements Delayed
    {
        @Override
        public long getDelay(TimeUnit unit)
        {
            long remaining = deliveryTime - System.currentTimeMillis();
            return unit.convert(remaining, TimeUnit.MILLISECONDS);
        }

        @Override
        public int compareTo(Delayed other)
        {
            if (other instanceof DelayedPacket otherPacket)
            {
                return Long.compare(this.deliveryTime, otherPacket.deliveryTime);
            }
            return Long.compare(getDelay(TimeUnit.MILLISECONDS), other.getDelay(TimeUnit.MILLISECONDS));
        }
    }
}
