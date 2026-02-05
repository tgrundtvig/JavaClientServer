package org.abstractica.clientserver.impl.transport;

import org.abstractica.clientserver.EndPoint;
import org.abstractica.clientserver.Network;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A simulated network that routes packets between endpoints.
 *
 * <p>Endpoints are created via the factory methods and packets are
 * routed by destination address. Supports network-wide conditions
 * like latency and packet loss that apply to all traffic.</p>
 *
 * <p>Usage:</p>
 * <pre>{@code
 * SimulatedNetwork network = new SimulatedNetwork();
 *
 * // Create test endpoints via factory methods
 * TestEndPoint server = network.createTestEndPoint(serverAddress);
 * TestEndPoint client1 = network.createTestEndPoint();
 * TestEndPoint client2 = network.createTestEndPoint();
 *
 * // Set network-wide conditions
 * network.setLatency(Duration.ofMillis(10));
 * network.setPacketLossRate(0.01);
 *
 * // Set per-endpoint conditions
 * client1.setPacketLossRate(0.1);
 * }</pre>
 *
 * @see UdpNetwork for production use
 */
public class SimulatedNetwork implements Network
{
    private static final Logger LOG = LoggerFactory.getLogger(SimulatedNetwork.class);

    private final Map<SocketAddress, SimulatedEndPoint> endpoints = new ConcurrentHashMap<>();

    // Network-wide conditions (applied in addition to per-endpoint conditions)
    private volatile double packetLossRate = 0.0;
    private volatile Duration minLatency = Duration.ZERO;
    private volatile Duration maxLatency = Duration.ZERO;

    /**
     * Creates a new simulated network with no conditions.
     */
    public SimulatedNetwork()
    {
    }

    // ========== Network Interface ==========

    @Override
    public EndPoint createEndPoint()
    {
        return new SimulatedEndPoint(this);
    }

    @Override
    public EndPoint createEndPoint(SocketAddress localAddress)
    {
        return new SimulatedEndPoint(localAddress, this);
    }

    // ========== TestEndPoint Factory Methods ==========

    /**
     * Creates a test endpoint with an auto-assigned local address.
     *
     * <p>Typically used for clients that don't need a specific port.</p>
     *
     * @return a new test endpoint
     */
    public TestEndPoint createTestEndPoint()
    {
        return new SimulatedEndPoint(this);
    }

    /**
     * Creates a test endpoint bound to the specified local address.
     *
     * <p>Typically used for servers that need a well-known address.</p>
     *
     * @param localAddress the local address to bind to
     * @return a new test endpoint
     */
    public TestEndPoint createTestEndPoint(SocketAddress localAddress)
    {
        return new SimulatedEndPoint(localAddress, this);
    }

    // ========== Endpoint Registration ==========

    /**
     * Registers an endpoint with this network.
     *
     * <p>Called automatically by SimulatedEndPoint constructor.</p>
     *
     * @param endpoint the endpoint to register
     */
    void register(SimulatedEndPoint endpoint)
    {
        SocketAddress address = endpoint.getLocalAddress();
        SimulatedEndPoint existing = endpoints.putIfAbsent(address, endpoint);
        if (existing != null)
        {
            throw new IllegalStateException("Address already in use: " + address);
        }
        LOG.debug("Endpoint registered: {}", address);
    }

    /**
     * Unregisters an endpoint from this network.
     *
     * <p>Called automatically when endpoint is closed.</p>
     *
     * @param endpoint the endpoint to unregister
     */
    void unregister(SimulatedEndPoint endpoint)
    {
        endpoints.remove(endpoint.getLocalAddress());
        LOG.debug("Endpoint unregistered: {}", endpoint.getLocalAddress());
    }

    /**
     * Looks up an endpoint by its address (internal use).
     *
     * <p>Handles wildcard addresses: if no exact match, checks for an endpoint
     * bound to the wildcard address (0.0.0.0) on the same port.</p>
     *
     * @param address the address to look up
     * @return the endpoint at that address, or null if not found
     */
    SimulatedEndPoint lookup(SocketAddress address)
    {
        SimulatedEndPoint endpoint = endpoints.get(address);
        if (endpoint != null)
        {
            return endpoint;
        }

        // Try wildcard address lookup
        return lookupWildcard(address);
    }

    /**
     * Gets a test endpoint by its local address.
     *
     * <p>Use this to access endpoint-specific features like
     * {@link TestEndPoint#dropNextPacket()} for testing.</p>
     *
     * <p>Handles wildcard addresses: if no exact match, checks for an endpoint
     * bound to the wildcard address (0.0.0.0) on the same port.</p>
     *
     * @param address the local address of the endpoint
     * @return the endpoint, or null if not found
     */
    public TestEndPoint getEndPoint(SocketAddress address)
    {
        SimulatedEndPoint endpoint = endpoints.get(address);
        if (endpoint != null)
        {
            return endpoint;
        }

        // Try wildcard address lookup
        return lookupWildcard(address);
    }

    /**
     * Attempts to find an endpoint bound to the wildcard address with the same port.
     */
    private SimulatedEndPoint lookupWildcard(SocketAddress address)
    {
        if (!(address instanceof InetSocketAddress inetAddr))
        {
            return null;
        }

        int port = inetAddr.getPort();

        // Check all endpoints for one bound to a wildcard address on this port
        for (Map.Entry<SocketAddress, SimulatedEndPoint> entry : endpoints.entrySet())
        {
            if (entry.getKey() instanceof InetSocketAddress boundAddr)
            {
                if (boundAddr.getPort() == port && boundAddr.getAddress().isAnyLocalAddress())
                {
                    return entry.getValue();
                }
            }
        }

        return null;
    }

    /**
     * Returns all registered endpoints.
     *
     * @return collection of registered endpoints
     */
    public Collection<? extends TestEndPoint> getEndPoints()
    {
        return endpoints.values();
    }

    /**
     * Returns the number of registered endpoints.
     *
     * @return endpoint count
     */
    public int getEndPointCount()
    {
        return endpoints.size();
    }

    // ========== Network-Wide Conditions ==========

    /**
     * Sets the network-wide packet loss rate.
     *
     * <p>This is applied in addition to per-endpoint packet loss.</p>
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
     * Returns the network-wide packet loss rate.
     *
     * @return packet loss rate (0.0 to 1.0)
     */
    public double getPacketLossRate()
    {
        return packetLossRate;
    }

    /**
     * Sets fixed network-wide latency.
     *
     * <p>This is added to per-endpoint latency.</p>
     *
     * @param latency the latency to apply
     */
    public void setLatency(Duration latency)
    {
        setLatency(latency, latency);
    }

    /**
     * Sets network-wide latency range.
     *
     * <p>Each packet gets a random latency between min and max,
     * added to any per-endpoint latency.</p>
     *
     * @param min minimum latency
     * @param max maximum latency
     */
    public void setLatency(Duration min, Duration max)
    {
        if (min == null || max == null)
        {
            throw new IllegalArgumentException("Latency cannot be null");
        }
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
     * Returns the minimum network-wide latency.
     *
     * @return minimum latency
     */
    public Duration getMinLatency()
    {
        return minLatency;
    }

    /**
     * Returns the maximum network-wide latency.
     *
     * @return maximum latency
     */
    public Duration getMaxLatency()
    {
        return maxLatency;
    }

    /**
     * Resets all network-wide conditions to defaults (no loss, no latency).
     */
    public void reset()
    {
        this.packetLossRate = 0.0;
        this.minLatency = Duration.ZERO;
        this.maxLatency = Duration.ZERO;
    }

    /**
     * Closes all endpoints on this network.
     */
    public void close()
    {
        for (SimulatedEndPoint endpoint : endpoints.values())
        {
            endpoint.close();
        }
        endpoints.clear();
    }
}
