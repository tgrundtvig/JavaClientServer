package org.abstractica.clientserver.impl.transport;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link SimulatedTransport}.
 */
class SimulatedTransportTest
{
    private SimulatedTransport transport1;
    private SimulatedTransport transport2;

    @BeforeEach
    void setUp()
    {
        transport1 = new SimulatedTransport(new InetSocketAddress("127.0.0.1", 10001));
        transport2 = new SimulatedTransport(new InetSocketAddress("127.0.0.1", 10002));

        // Connect them to each other
        transport1.connectTo(transport2);
        transport2.connectTo(transport1);
    }

    @AfterEach
    void tearDown()
    {
        transport1.close();
        transport2.close();
    }

    // ========== Basic Functionality ==========

    @Test
    void canSendAndReceivePackets() throws Exception
    {
        CountDownLatch received = new CountDownLatch(1);
        AtomicReference<byte[]> receivedData = new AtomicReference<>();
        AtomicReference<SocketAddress> receivedFrom = new AtomicReference<>();

        transport2.setReceiveHandler((data, from) ->
        {
            byte[] copy = new byte[data.remaining()];
            data.get(copy);
            receivedData.set(copy);
            receivedFrom.set(from);
            received.countDown();
        });

        transport1.setReceiveHandler((data, from) -> {});
        transport1.start();
        transport2.start();

        // Send from transport1 to transport2
        byte[] message = "Hello, World!".getBytes();
        transport1.send(ByteBuffer.wrap(message), transport2.getLocalAddress());

        assertTrue(received.await(1, TimeUnit.SECONDS), "Should receive packet");
        assertArrayEquals(message, receivedData.get());
        assertEquals(transport1.getLocalAddress(), receivedFrom.get());
    }

    @Test
    void bidirectionalCommunication() throws Exception
    {
        CountDownLatch received1 = new CountDownLatch(1);
        CountDownLatch received2 = new CountDownLatch(1);

        transport1.setReceiveHandler((data, from) -> received1.countDown());
        transport2.setReceiveHandler((data, from) -> received2.countDown());
        transport1.start();
        transport2.start();

        // Send in both directions
        transport1.send(ByteBuffer.wrap(new byte[]{1}), transport2.getLocalAddress());
        transport2.send(ByteBuffer.wrap(new byte[]{2}), transport1.getLocalAddress());

        assertTrue(received1.await(1, TimeUnit.SECONDS));
        assertTrue(received2.await(1, TimeUnit.SECONDS));
    }

    // ========== Packet Loss ==========

    @Test
    void dropNextPacket() throws Exception
    {
        CountDownLatch received = new CountDownLatch(1);

        transport2.setReceiveHandler((data, from) -> received.countDown());
        transport1.setReceiveHandler((data, from) -> {});
        transport1.start();
        transport2.start();

        // First packet should be dropped
        transport1.dropNextPacket();
        transport1.send(ByteBuffer.wrap(new byte[]{1}), transport2.getLocalAddress());

        // Second packet should arrive
        transport1.send(ByteBuffer.wrap(new byte[]{2}), transport2.getLocalAddress());

        assertTrue(received.await(1, TimeUnit.SECONDS), "Second packet should arrive");
        assertEquals(2, transport1.getPacketsSent());
        assertEquals(1, transport1.getPacketsDropped());
    }

    @Test
    void packetLossRate() throws Exception
    {
        AtomicInteger receivedCount = new AtomicInteger(0);

        transport2.setReceiveHandler((data, from) -> receivedCount.incrementAndGet());
        transport1.setReceiveHandler((data, from) -> {});
        transport1.start();
        transport2.start();

        // Set 50% packet loss
        transport1.setPacketLossRate(0.5);

        // Send many packets
        int totalPackets = 100;
        for (int i = 0; i < totalPackets; i++)
        {
            transport1.send(ByteBuffer.wrap(new byte[]{(byte) i}), transport2.getLocalAddress());
        }

        // Wait for delivery
        Thread.sleep(500);

        // With 50% loss, we expect roughly half to arrive
        int received = receivedCount.get();
        assertTrue(received > 20 && received < 80,
                "Expected roughly 50% delivery, got: " + received + "/" + totalPackets);

        assertEquals(totalPackets, transport1.getPacketsSent());
        assertTrue(transport1.getPacketsDropped() > 20);
    }

    // ========== Latency ==========

    @Test
    void fixedLatency() throws Exception
    {
        CountDownLatch received = new CountDownLatch(1);
        AtomicReference<Long> receiveTime = new AtomicReference<>();

        transport2.setReceiveHandler((data, from) ->
        {
            receiveTime.set(System.currentTimeMillis());
            received.countDown();
        });
        transport1.setReceiveHandler((data, from) -> {});
        transport1.start();
        transport2.start();

        // Set 100ms latency
        transport1.setLatency(Duration.ofMillis(100));

        long sendTime = System.currentTimeMillis();
        transport1.send(ByteBuffer.wrap(new byte[]{1}), transport2.getLocalAddress());

        assertTrue(received.await(1, TimeUnit.SECONDS));

        long elapsed = receiveTime.get() - sendTime;
        assertTrue(elapsed >= 90, "Should have at least ~100ms latency, got: " + elapsed + "ms");
    }

    @Test
    void latencyRange() throws Exception
    {
        CountDownLatch received = new CountDownLatch(10);
        AtomicInteger deliveredCount = new AtomicInteger(0);

        transport2.setReceiveHandler((data, from) ->
        {
            deliveredCount.incrementAndGet();
            received.countDown();
        });
        transport1.setReceiveHandler((data, from) -> {});
        transport1.start();
        transport2.start();

        // Set 50-150ms latency range
        transport1.setLatency(Duration.ofMillis(50), Duration.ofMillis(150));

        long startTime = System.currentTimeMillis();
        for (int i = 0; i < 10; i++)
        {
            transport1.send(ByteBuffer.wrap(new byte[]{(byte) i}), transport2.getLocalAddress());
        }

        assertTrue(received.await(2, TimeUnit.SECONDS));

        // All should be delivered after ~150ms
        long elapsed = System.currentTimeMillis() - startTime;
        assertTrue(elapsed >= 50, "Should have at least 50ms minimum latency");
    }

    @Test
    void delayNextPacket() throws Exception
    {
        CountDownLatch received = new CountDownLatch(1);
        AtomicReference<Long> receiveTime = new AtomicReference<>();

        transport2.setReceiveHandler((data, from) ->
        {
            receiveTime.set(System.currentTimeMillis());
            received.countDown();
        });
        transport1.setReceiveHandler((data, from) -> {});
        transport1.start();
        transport2.start();

        // Delay next packet by 200ms
        transport1.delayNextPacket(Duration.ofMillis(200));

        long sendTime = System.currentTimeMillis();
        transport1.send(ByteBuffer.wrap(new byte[]{1}), transport2.getLocalAddress());

        assertTrue(received.await(1, TimeUnit.SECONDS), "Packet should arrive");

        long elapsed = receiveTime.get() - sendTime;
        assertTrue(elapsed >= 180, "Should have at least ~200ms delay, got: " + elapsed + "ms");
    }

    // ========== Reordering ==========

    @Test
    void packetReordering() throws Exception
    {
        AtomicInteger receivedCount = new AtomicInteger(0);

        transport2.setReceiveHandler((data, from) -> receivedCount.incrementAndGet());
        transport1.setReceiveHandler((data, from) -> {});
        transport1.start();
        transport2.start();

        // Set 100% reordering with 100ms delay
        transport1.setReorderRate(1.0);
        transport1.setReorderDelay(Duration.ofMillis(100));

        long startTime = System.currentTimeMillis();
        transport1.send(ByteBuffer.wrap(new byte[]{1}), transport2.getLocalAddress());

        // Wait for delivery (should take at least 100ms due to reorder delay)
        Thread.sleep(200);

        assertEquals(1, receivedCount.get());
        long elapsed = System.currentTimeMillis() - startTime;
        assertTrue(elapsed >= 100, "Reordered packet should have extra delay");
    }

    // ========== Statistics ==========

    @Test
    void statisticsTracking() throws Exception
    {
        CountDownLatch received = new CountDownLatch(4);

        transport2.setReceiveHandler((data, from) -> received.countDown());
        transport1.setReceiveHandler((data, from) -> {});
        transport1.start();
        transport2.start();

        // Send 6 packets, dropping packets at index 0 and 2
        for (int i = 0; i < 6; i++)
        {
            if (i == 0 || i == 2)
            {
                transport1.dropNextPacket();
            }
            transport1.send(ByteBuffer.wrap(new byte[]{(byte) i}), transport2.getLocalAddress());
        }

        assertTrue(received.await(1, TimeUnit.SECONDS));

        assertEquals(6, transport1.getPacketsSent());
        assertEquals(2, transport1.getPacketsDropped()); // Packets at index 0 and 2
        assertEquals(4, transport2.getPacketsDelivered());
    }

    @Test
    void resetClearsConfiguration() throws Exception
    {
        transport1.setPacketLossRate(0.5);
        transport1.setLatency(Duration.ofMillis(100));
        transport1.setReorderRate(0.3);
        transport1.dropNextPacket();

        transport1.reset();

        // After reset, packets should be delivered immediately without loss
        CountDownLatch received = new CountDownLatch(1);
        transport2.setReceiveHandler((data, from) -> received.countDown());
        transport1.setReceiveHandler((data, from) -> {});
        transport1.start();
        transport2.start();

        long start = System.currentTimeMillis();
        transport1.send(ByteBuffer.wrap(new byte[]{1}), transport2.getLocalAddress());

        assertTrue(received.await(100, TimeUnit.MILLISECONDS), "Should receive quickly after reset");
        assertEquals(0, transport1.getPacketsDropped());
    }

    // ========== Error Handling ==========

    @Test
    void throwsIfNotStarted()
    {
        assertThrows(IllegalStateException.class, () ->
                transport1.send(ByteBuffer.wrap(new byte[]{1}), transport2.getLocalAddress()));
    }

    @Test
    void throwsIfNoReceiveHandler()
    {
        assertThrows(IllegalStateException.class, () -> transport1.start());
    }

    @Test
    void invalidPacketLossRate()
    {
        assertThrows(IllegalArgumentException.class, () -> transport1.setPacketLossRate(-0.1));
        assertThrows(IllegalArgumentException.class, () -> transport1.setPacketLossRate(1.1));
    }

    @Test
    void invalidLatency()
    {
        assertThrows(IllegalArgumentException.class, () ->
                transport1.setLatency(Duration.ofMillis(100), Duration.ofMillis(50)));
        assertThrows(IllegalArgumentException.class, () ->
                transport1.setLatency(Duration.ofMillis(-1)));
    }
}
