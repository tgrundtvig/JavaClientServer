package org.abstractica.clientserver.impl.transport;

import org.abstractica.clientserver.EndPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/**
 * UDP-based transport implementation using NIO.
 *
 * <p>This transport uses a single thread for receiving packets and
 * dispatches them to the configured handler. Sending is done directly
 * from the calling thread.</p>
 */
public class UdpEndPoint implements EndPoint
{
    private static final Logger LOG = LoggerFactory.getLogger(UdpEndPoint.class);
    private static final int DEFAULT_BUFFER_SIZE = 65536;

    private final InetSocketAddress bindAddress;
    private final int receiveBufferSize;

    private DatagramChannel channel;
    private Selector selector;
    private Thread receiveThread;
    private BiConsumer<ByteBuffer, SocketAddress> receiveHandler;
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * Creates a UDP transport that binds to a specific address.
     *
     * @param bindAddress the address and port to bind to
     */
    public UdpEndPoint(InetSocketAddress bindAddress)
    {
        this(bindAddress, DEFAULT_BUFFER_SIZE);
    }

    /**
     * Creates a UDP transport with custom buffer size.
     *
     * @param bindAddress       the address and port to bind to
     * @param receiveBufferSize the size of the receive buffer
     */
    public UdpEndPoint(InetSocketAddress bindAddress, int receiveBufferSize)
    {
        this.bindAddress = Objects.requireNonNull(bindAddress, "bindAddress");
        this.receiveBufferSize = receiveBufferSize;
    }

    /**
     * Creates a UDP transport for client use (ephemeral port).
     *
     * @return a new client transport
     */
    public static UdpEndPoint client()
    {
        return new UdpEndPoint(new InetSocketAddress(0));
    }

    /**
     * Creates a UDP transport for server use.
     *
     * @param port the port to bind to
     * @return a new server transport
     */
    public static UdpEndPoint server(int port)
    {
        return new UdpEndPoint(new InetSocketAddress(port));
    }

    /**
     * Creates a UDP transport for server use with specific bind address.
     *
     * @param address the address to bind to
     * @param port    the port to bind to
     * @return a new server transport
     */
    public static UdpEndPoint server(String address, int port)
    {
        return new UdpEndPoint(new InetSocketAddress(address, port));
    }

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

        try
        {
            channel = DatagramChannel.open();
            channel.configureBlocking(false);
            channel.bind(bindAddress);

            selector = Selector.open();
            channel.register(selector, SelectionKey.OP_READ);

            receiveThread = new Thread(this::receiveLoop, "udp-transport-" + channel.getLocalAddress());
            receiveThread.setDaemon(true);
            receiveThread.start();

            LOG.info("UDP transport started on {}", channel.getLocalAddress());
        }
        catch (IOException e)
        {
            running.set(false);
            throw new UncheckedIOException("Failed to start transport", e);
        }
    }

    @Override
    public void send(ByteBuffer data, SocketAddress destination)
    {
        if (!running.get())
        {
            throw new IllegalStateException("Transport not running");
        }

        try
        {
            channel.send(data, destination);
        }
        catch (IOException e)
        {
            LOG.warn("Failed to send packet to {}: {}", destination, e.getMessage());
        }
    }

    @Override
    public void close()
    {
        if (!running.compareAndSet(true, false))
        {
            return;
        }

        LOG.info("Closing UDP transport");

        // Wake up selector to exit receive loop
        if (selector != null)
        {
            selector.wakeup();
        }

        // Wait for receive thread to finish
        if (receiveThread != null)
        {
            try
            {
                receiveThread.join(1000);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
            }
        }

        // Close resources
        if (selector != null)
        {
            try
            {
                selector.close();
            }
            catch (IOException e)
            {
                LOG.warn("Error closing selector", e);
            }
        }

        if (channel != null)
        {
            try
            {
                channel.close();
            }
            catch (IOException e)
            {
                LOG.warn("Error closing channel", e);
            }
        }
    }

    @Override
    public SocketAddress getLocalAddress()
    {
        if (channel == null)
        {
            return null;
        }
        try
        {
            return channel.getLocalAddress();
        }
        catch (IOException e)
        {
            return null;
        }
    }

    private void receiveLoop()
    {
        ByteBuffer buffer = ByteBuffer.allocateDirect(receiveBufferSize);

        while (running.get())
        {
            try
            {
                int selected = selector.select(100); // 100ms timeout for shutdown check
                if (selected == 0)
                {
                    continue;
                }

                selector.selectedKeys().clear();

                buffer.clear();
                SocketAddress source = channel.receive(buffer);
                if (source != null)
                {
                    buffer.flip();
                    try
                    {
                        receiveHandler.accept(buffer, source);
                    }
                    catch (Exception e)
                    {
                        LOG.error("Error in receive handler", e);
                    }
                }
            }
            catch (IOException e)
            {
                if (running.get())
                {
                    LOG.error("Error receiving packet", e);
                }
            }
        }

        LOG.debug("Receive loop exited");
    }
}
