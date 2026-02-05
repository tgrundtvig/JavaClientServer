package org.abstractica.clientserver.impl.transport;

import org.abstractica.clientserver.EndPoint;
import org.abstractica.clientserver.Network;

import java.net.SocketAddress;
import java.time.Duration;
import java.util.Objects;

/**
 * Decorator that wraps any {@link Network} to produce lossy endpoints.
 *
 * <p>Every endpoint created through this network will be wrapped in a
 * {@link LossyEndPoint} that simulates packet loss and delay on
 * outgoing sends.</p>
 *
 * @see LossyEndPoint
 */
public class LossyNetwork implements Network
{
    private final Network delegate;
    private final double lossRate;
    private final Duration delay;

    /**
     * Creates a lossy network decorator.
     *
     * @param delegate the network to wrap
     * @param lossRate probability of dropping a packet (0.0 to 1.0)
     * @param delay    delay before sending each non-dropped packet
     */
    public LossyNetwork(Network delegate, double lossRate, Duration delay)
    {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.lossRate = lossRate;
        this.delay = Objects.requireNonNull(delay, "delay");
    }

    @Override
    public EndPoint createEndPoint()
    {
        return new LossyEndPoint(delegate.createEndPoint(), lossRate, delay);
    }

    @Override
    public EndPoint createEndPoint(SocketAddress localAddress)
    {
        return new LossyEndPoint(delegate.createEndPoint(localAddress), lossRate, delay);
    }
}
