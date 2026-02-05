package org.abstractica.clientserver.impl.transport;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

/**
 * Factory for creating real UDP endpoints.
 *
 * <p>Use this for production code where actual network communication is needed.</p>
 *
 * <p>Usage:</p>
 * <pre>{@code
 * Network network = new UdpNetwork();
 *
 * // Server with specific address
 * EndPoint serverEndPoint = network.createEndPoint(
 *     new InetSocketAddress("0.0.0.0", 17777));
 *
 * // Client with auto-assigned address
 * EndPoint clientEndPoint = network.createEndPoint();
 * }</pre>
 *
 * @see SimulatedNetwork for testing
 */
public class UdpNetwork implements Network
{
    /**
     * Creates a new UDP network factory.
     */
    public UdpNetwork()
    {
    }

    @Override
    public EndPoint createEndPoint()
    {
        return UdpEndPoint.client();
    }

    @Override
    public EndPoint createEndPoint(SocketAddress localAddress)
    {
        if (localAddress instanceof InetSocketAddress inetAddress)
        {
            return new UdpEndPoint(inetAddress);
        }
        throw new IllegalArgumentException(
                "localAddress must be an InetSocketAddress: " + localAddress.getClass());
    }
}
