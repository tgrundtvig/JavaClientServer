package org.abstractica.clientserver.impl.transport;

import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.function.BiConsumer;

/**
 * A network endpoint for sending and receiving raw bytes.
 *
 * <p>EndPoint represents a bound socket at a specific address/port that can
 * send packets to destinations and receive packets from any source. It handles
 * physical network communication without knowledge of packet structure,
 * encryption, or reliability.</p>
 *
 * @see TestEndPoint for testing with simulated network conditions
 */
public interface EndPoint extends AutoCloseable
{
    /**
     * Sends data to a destination address.
     *
     * <p>This method is thread-safe and may be called from any thread.</p>
     *
     * @param data        the data to send (position to limit)
     * @param destination the destination address
     */
    void send(ByteBuffer data, SocketAddress destination);

    /**
     * Sets the handler for received data.
     *
     * <p>The handler will be called from the endpoint's I/O thread
     * whenever data is received. The handler must not block.</p>
     *
     * <p>The ByteBuffer passed to the handler is only valid during the
     * callback. If the data needs to be retained, copy it.</p>
     *
     * @param handler called with (data, sourceAddress) for each received packet
     */
    void setReceiveHandler(BiConsumer<ByteBuffer, SocketAddress> handler);

    /**
     * Starts the endpoint.
     *
     * <p>For server endpoints, this binds to the configured port and begins
     * accepting packets. For client endpoints, this opens the socket.</p>
     */
    void start();

    /**
     * Closes the endpoint and releases resources.
     */
    @Override
    void close();

    /**
     * Returns the local address this endpoint is bound to.
     *
     * @return the local socket address, or null if not bound
     */
    SocketAddress getLocalAddress();
}
