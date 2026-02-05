package org.abstractica.clientserver;

import java.net.SocketAddress;

/**
 * A network that can create endpoints.
 *
 * <p>Implementations provide different network backends for production
 * and testing scenarios.</p>
 *
 * <p>Use {@link NetworkFactory} to obtain Network instances.</p>
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
