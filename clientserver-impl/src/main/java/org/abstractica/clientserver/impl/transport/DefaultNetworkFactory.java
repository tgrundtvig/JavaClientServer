package org.abstractica.clientserver.impl.transport;

import org.abstractica.clientserver.Network;
import org.abstractica.clientserver.NetworkFactory;

/**
 * Default implementation of NetworkFactory.
 *
 * <p>Creates UDP and simulated network instances.</p>
 */
public class DefaultNetworkFactory implements NetworkFactory
{
    @Override
    public Network createUdpNetwork()
    {
        return new UdpNetwork();
    }

    @Override
    public Network createSimulatedNetwork()
    {
        return new SimulatedNetwork();
    }
}
