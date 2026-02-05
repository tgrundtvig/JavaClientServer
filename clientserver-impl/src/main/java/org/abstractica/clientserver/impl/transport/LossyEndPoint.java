package org.abstractica.clientserver.impl.transport;

import org.abstractica.clientserver.EndPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiConsumer;

/**
 * Decorator that simulates a lossy network on outgoing sends.
 *
 * <p>Wraps any {@link EndPoint} and applies packet loss and delay to
 * {@link #send(ByteBuffer, SocketAddress)} calls. All other methods
 * delegate directly to the wrapped endpoint.</p>
 *
 * <p>Loss and delay are outbound-only — if both client and server use
 * a lossy endpoint, both directions degrade independently.</p>
 */
public class LossyEndPoint implements EndPoint
{
    private static final Logger LOG = LoggerFactory.getLogger(LossyEndPoint.class);

    private final EndPoint delegate;
    private final double lossRate;
    private final Duration delay;

    /**
     * Creates a lossy endpoint decorator.
     *
     * @param delegate the endpoint to wrap
     * @param lossRate probability of dropping a packet (0.0 to 1.0)
     * @param delay    delay before sending each non-dropped packet
     */
    public LossyEndPoint(EndPoint delegate, double lossRate, Duration delay)
    {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.lossRate = lossRate;
        this.delay = Objects.requireNonNull(delay, "delay");
    }

    @Override
    public void send(ByteBuffer data, SocketAddress destination)
    {
        if (ThreadLocalRandom.current().nextDouble() < lossRate)
        {
            LOG.debug("LOSSY: Dropped packet to {}", destination);
            return;
        }

        // Copy the data since the caller's buffer may be reused
        ByteBuffer copy = ByteBuffer.allocate(data.remaining());
        copy.put(data);
        copy.flip();

        Thread.startVirtualThread(() ->
        {
            try
            {
                Thread.sleep(delay);
                delegate.send(copy, destination);
                LOG.debug("LOSSY: Delayed packet to {} by {}ms", destination, delay.toMillis());
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
            }
        });
    }

    @Override
    public void setReceiveHandler(BiConsumer<ByteBuffer, SocketAddress> handler)
    {
        delegate.setReceiveHandler(handler);
    }

    @Override
    public void start()
    {
        delegate.start();
    }

    @Override
    public void close()
    {
        delegate.close();
    }

    @Override
    public SocketAddress getLocalAddress()
    {
        return delegate.getLocalAddress();
    }
}
