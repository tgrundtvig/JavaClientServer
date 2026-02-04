package org.abstractica.clientserver.impl.transport;

import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.function.BiConsumer;

/**
 * Low-level transport for sending and receiving raw bytes.
 *
 * <p>Transport handles the physical network communication without any
 * knowledge of packet structure, encryption, or reliability. It simply
 * moves bytes between endpoints.</p>
 */
public interface Transport extends AutoCloseable
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
     * <p>The handler will be called from the transport's I/O thread
     * whenever data is received. The handler must not block.</p>
     *
     * <p>The ByteBuffer passed to the handler is only valid during the
     * callback. If the data needs to be retained, copy it.</p>
     *
     * @param handler called with (data, sourceAddress) for each received packet
     */
    void setReceiveHandler(BiConsumer<ByteBuffer, SocketAddress> handler);

    /**
     * Starts the transport.
     *
     * <p>For server transports, this binds to the configured port and begins
     * accepting packets. For client transports, this opens the socket.</p>
     */
    void start();

    /**
     * Closes the transport and releases resources.
     */
    @Override
    void close();

    /**
     * Returns the local address this transport is bound to.
     *
     * @return the local socket address, or null if not bound
     */
    SocketAddress getLocalAddress();
}
