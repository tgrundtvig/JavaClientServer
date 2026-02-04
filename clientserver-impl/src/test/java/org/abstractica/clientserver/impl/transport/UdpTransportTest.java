package org.abstractica.clientserver.impl.transport;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link UdpTransport}.
 */
class UdpTransportTest
{
    private UdpTransport transport1;
    private UdpTransport transport2;

    @AfterEach
    void tearDown()
    {
        if (transport1 != null)
        {
            transport1.close();
        }
        if (transport2 != null)
        {
            transport2.close();
        }
    }

    @Test
    void sendAndReceive_singlePacket() throws InterruptedException
    {
        // Set up receiver
        BlockingQueue<ReceivedPacket> received = new ArrayBlockingQueue<>(10);
        transport1 = UdpTransport.server(0); // ephemeral port
        transport1.setReceiveHandler((data, source) ->
        {
            byte[] copy = new byte[data.remaining()];
            data.get(copy);
            received.add(new ReceivedPacket(copy, source));
        });
        transport1.start();

        // Set up sender
        transport2 = UdpTransport.client();
        transport2.setReceiveHandler((data, source) -> {});
        transport2.start();

        // Send packet
        byte[] payload = {0x01, 0x02, 0x03, 0x04};
        ByteBuffer buffer = ByteBuffer.wrap(payload);
        transport2.send(buffer, transport1.getLocalAddress());

        // Wait for receive
        ReceivedPacket packet = received.poll(1, TimeUnit.SECONDS);

        assertNotNull(packet, "Should have received packet");
        assertArrayEquals(payload, packet.data);
    }

    @Test
    void sendAndReceive_multiplePackets() throws InterruptedException
    {
        BlockingQueue<ReceivedPacket> received = new ArrayBlockingQueue<>(10);
        transport1 = UdpTransport.server(0);
        transport1.setReceiveHandler((data, source) ->
        {
            byte[] copy = new byte[data.remaining()];
            data.get(copy);
            received.add(new ReceivedPacket(copy, source));
        });
        transport1.start();

        transport2 = UdpTransport.client();
        transport2.setReceiveHandler((data, source) -> {});
        transport2.start();

        // Send multiple packets
        for (int i = 0; i < 5; i++)
        {
            ByteBuffer buffer = ByteBuffer.wrap(new byte[]{(byte) i});
            transport2.send(buffer, transport1.getLocalAddress());
        }

        // Receive all packets
        for (int i = 0; i < 5; i++)
        {
            ReceivedPacket packet = received.poll(1, TimeUnit.SECONDS);
            assertNotNull(packet, "Should have received packet " + i);
        }
    }

    @Test
    void sendAndReceive_bidirectional() throws InterruptedException
    {
        BlockingQueue<ReceivedPacket> received1 = new ArrayBlockingQueue<>(10);
        BlockingQueue<ReceivedPacket> received2 = new ArrayBlockingQueue<>(10);

        transport1 = UdpTransport.server(0);
        transport1.setReceiveHandler((data, source) ->
        {
            byte[] copy = new byte[data.remaining()];
            data.get(copy);
            received1.add(new ReceivedPacket(copy, source));
        });
        transport1.start();

        transport2 = UdpTransport.client();
        transport2.setReceiveHandler((data, source) ->
        {
            byte[] copy = new byte[data.remaining()];
            data.get(copy);
            received2.add(new ReceivedPacket(copy, source));
        });
        transport2.start();

        // Send from transport2 to transport1
        transport2.send(ByteBuffer.wrap(new byte[]{0x01}), transport1.getLocalAddress());
        ReceivedPacket packet1 = received1.poll(1, TimeUnit.SECONDS);
        assertNotNull(packet1);

        // Send reply from transport1 to transport2
        transport1.send(ByteBuffer.wrap(new byte[]{0x02}), packet1.source);
        ReceivedPacket packet2 = received2.poll(1, TimeUnit.SECONDS);
        assertNotNull(packet2);
        assertArrayEquals(new byte[]{0x02}, packet2.data);
    }

    @Test
    void sendAndReceive_largePacket() throws InterruptedException
    {
        BlockingQueue<ReceivedPacket> received = new ArrayBlockingQueue<>(10);
        transport1 = UdpTransport.server(0);
        transport1.setReceiveHandler((data, source) ->
        {
            byte[] copy = new byte[data.remaining()];
            data.get(copy);
            received.add(new ReceivedPacket(copy, source));
        });
        transport1.start();

        transport2 = UdpTransport.client();
        transport2.setReceiveHandler((data, source) -> {});
        transport2.start();

        // Send large packet (close to typical MTU)
        byte[] payload = new byte[1400];
        for (int i = 0; i < payload.length; i++)
        {
            payload[i] = (byte) (i % 256);
        }

        transport2.send(ByteBuffer.wrap(payload), transport1.getLocalAddress());
        ReceivedPacket packet = received.poll(1, TimeUnit.SECONDS);

        assertNotNull(packet);
        assertArrayEquals(payload, packet.data);
    }

    @Test
    void getLocalAddress_returnsBindAddress()
    {
        transport1 = UdpTransport.server(0);
        transport1.setReceiveHandler((data, source) -> {});
        transport1.start();

        SocketAddress address = transport1.getLocalAddress();
        assertNotNull(address);
        assertInstanceOf(InetSocketAddress.class, address);

        InetSocketAddress inetAddress = (InetSocketAddress) address;
        assertTrue(inetAddress.getPort() > 0);
    }

    @Test
    void getLocalAddress_beforeStart_returnsNull()
    {
        transport1 = UdpTransport.server(0);
        assertNull(transport1.getLocalAddress());
    }

    @Test
    void start_withoutHandler_throws()
    {
        transport1 = UdpTransport.server(0);
        assertThrows(IllegalStateException.class, () -> transport1.start());
    }

    @Test
    void start_twice_throws()
    {
        transport1 = UdpTransport.server(0);
        transport1.setReceiveHandler((data, source) -> {});
        transport1.start();

        assertThrows(IllegalStateException.class, () -> transport1.start());
    }

    @Test
    void send_beforeStart_throws()
    {
        transport1 = UdpTransport.server(0);
        transport1.setReceiveHandler((data, source) -> {});

        assertThrows(IllegalStateException.class, () ->
                transport1.send(ByteBuffer.wrap(new byte[1]), new InetSocketAddress("localhost", 9999)));
    }

    @Test
    void close_stopsReceiving() throws InterruptedException
    {
        BlockingQueue<ReceivedPacket> received = new ArrayBlockingQueue<>(10);
        transport1 = UdpTransport.server(0);
        transport1.setReceiveHandler((data, source) ->
        {
            byte[] copy = new byte[data.remaining()];
            data.get(copy);
            received.add(new ReceivedPacket(copy, source));
        });
        transport1.start();

        SocketAddress address = transport1.getLocalAddress();
        transport1.close();

        // Try to send to closed transport
        transport2 = UdpTransport.client();
        transport2.setReceiveHandler((data, source) -> {});
        transport2.start();
        transport2.send(ByteBuffer.wrap(new byte[]{0x01}), address);

        // Should not receive anything
        ReceivedPacket packet = received.poll(200, TimeUnit.MILLISECONDS);
        assertNull(packet);
    }

    @Test
    void close_canBeCalledMultipleTimes()
    {
        transport1 = UdpTransport.server(0);
        transport1.setReceiveHandler((data, source) -> {});
        transport1.start();

        transport1.close();
        transport1.close(); // Should not throw
        transport1.close(); // Should not throw
    }

    @Test
    void factoryMethods()
    {
        transport1 = UdpTransport.client();
        assertNotNull(transport1);

        transport2 = UdpTransport.server(0);
        assertNotNull(transport2);
    }

    /**
     * Helper record for capturing received packets.
     */
    private record ReceivedPacket(byte[] data, SocketAddress source) {}
}
