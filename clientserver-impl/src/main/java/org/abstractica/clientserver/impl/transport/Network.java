package org.abstractica.clientserver.impl.transport;

import java.net.SocketAddress;

/**
 * A network that can create endpoints.
 *
 * <p>Implementations provide different network backends:</p>
 * <ul>
 *   <li>{@link UdpNetwork} - real UDP sockets for production</li>
 *   <li>{@link SimulatedNetwork} - simulated network for testing</li>
 * </ul>
 *
 * <p>Usage:</p>
 * <pre>{@code
 * // For production
 * Network network = new UdpNetwork();
 *
 * // For testing
 * Network network = new SimulatedNetwork();
 *
 * // Create endpoints
 * EndPoint serverEndPoint = network.createEndPoint(serverAddress);
 * EndPoint clientEndPoint = network.createEndPoint();
 * }</pre>
 */
public interface Network
{
    /**
     * Creates an endpoint with an auto-assigned local address.
     *
     * <p>Typically used for clients that don't need a specific port.</p>
     *
     * @return a new endpoint
     */
    EndPoint createEndPoint();

    /**
     * Creates an endpoint bound to the specified local address.
     *
     * <p>Typically used for servers that need a well-known address.</p>
     *
     * @param localAddress the local address to bind to
     * @return a new endpoint
     */
    EndPoint createEndPoint(SocketAddress localAddress);
}
