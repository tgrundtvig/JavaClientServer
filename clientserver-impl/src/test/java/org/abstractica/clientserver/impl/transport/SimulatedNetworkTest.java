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
 * Tests for {@link SimulatedNetwork} with multiple endpoints.
 */
class SimulatedNetworkTest
{
    private SimulatedNetwork network;

    @BeforeEach
    void setUp()
    {
        network = new SimulatedNetwork();
    }

    @AfterEach
    void tearDown()
    {
        network.close();
    }

    // ========== Registration ==========

    @Test
    void endPointRegistersOnCreation()
    {
        TestEndPoint endPoint = network.createTestEndPoint();

        assertEquals(1, network.getEndPointCount());
        assertTrue(network.getEndPoints().contains(endPoint));
    }

    @Test
    void multipleEndPointsCanRegister()
    {
        network.createTestEndPoint();
        network.createTestEndPoint();
        network.createTestEndPoint();

        assertEquals(3, network.getEndPointCount());
    }

    @Test
    void endPointUnregistersOnClose()
    {
        TestEndPoint endPoint = network.createTestEndPoint();
        endPoint.setReceiveHandler((data, from) -> {});
        endPoint.start();

        assertEquals(1, network.getEndPointCount());

        endPoint.close();

        assertEquals(0, network.getEndPointCount());
    }

    @Test
    void duplicateAddressThrows()
    {
        SocketAddress address = new InetSocketAddress("127.0.0.1", 12345);
        network.createTestEndPoint(address);

        assertThrows(IllegalStateException.class, () ->
                network.createTestEndPoint(address));
    }

    // ========== Packet Routing ==========

    @Test
    void packetRoutedByDestinationAddress() throws Exception
    {
        SocketAddress serverAddr = new InetSocketAddress("127.0.0.1", 17777);
        TestEndPoint server = network.createTestEndPoint(serverAddr);
        TestEndPoint client = network.createTestEndPoint();

        CountDownLatch received = new CountDownLatch(1);
        AtomicReference<byte[]> receivedData = new AtomicReference<>();
        AtomicReference<SocketAddress> receivedFrom = new AtomicReference<>();

        server.setReceiveHandler((data, from) ->
        {
            byte[] copy = new byte[data.remaining()];
            data.get(copy);
            receivedData.set(copy);
            receivedFrom.set(from);
            received.countDown();
        });
        client.setReceiveHandler((data, from) -> {});

        server.start();
        client.start();

        // Send from client to server
        byte[] message = "Hello Server!".getBytes();
        client.send(ByteBuffer.wrap(message), serverAddr);

        assertTrue(received.await(1, TimeUnit.SECONDS));
        assertArrayEquals(message, receivedData.get());
        assertEquals(client.getLocalAddress(), receivedFrom.get());
    }

    @Test
    void multipleClientsCanReachServer() throws Exception
    {
        SocketAddress serverAddr = new InetSocketAddress("127.0.0.1", 17777);
        TestEndPoint server = network.createTestEndPoint(serverAddr);
        TestEndPoint client1 = network.createTestEndPoint();
        TestEndPoint client2 = network.createTestEndPoint();

        AtomicInteger receivedCount = new AtomicInteger(0);
        CountDownLatch bothReceived = new CountDownLatch(2);

        server.setReceiveHandler((data, from) ->
        {
            receivedCount.incrementAndGet();
            bothReceived.countDown();
        });
        client1.setReceiveHandler((data, from) -> {});
        client2.setReceiveHandler((data, from) -> {});

        server.start();
        client1.start();
        client2.start();

        // Send from both clients
        client1.send(ByteBuffer.wrap(new byte[]{1}), serverAddr);
        client2.send(ByteBuffer.wrap(new byte[]{2}), serverAddr);

        assertTrue(bothReceived.await(1, TimeUnit.SECONDS));
        assertEquals(2, receivedCount.get());
    }

    @Test
    void serverCanRespondToMultipleClients() throws Exception
    {
        SocketAddress serverAddr = new InetSocketAddress("127.0.0.1", 17777);
        TestEndPoint server = network.createTestEndPoint(serverAddr);
        TestEndPoint client1 = network.createTestEndPoint();
        TestEndPoint client2 = network.createTestEndPoint();

        CountDownLatch client1Received = new CountDownLatch(1);
        CountDownLatch client2Received = new CountDownLatch(1);

        server.setReceiveHandler((data, from) ->
        {
            // Echo back to sender
            byte[] response = new byte[data.remaining()];
            data.get(response);
            server.send(ByteBuffer.wrap(response), from);
        });
        client1.setReceiveHandler((data, from) -> client1Received.countDown());
        client2.setReceiveHandler((data, from) -> client2Received.countDown());

        server.start();
        client1.start();
        client2.start();

        // Both clients send to server
        client1.send(ByteBuffer.wrap(new byte[]{1}), serverAddr);
        client2.send(ByteBuffer.wrap(new byte[]{2}), serverAddr);

        // Both should receive responses
        assertTrue(client1Received.await(1, TimeUnit.SECONDS));
        assertTrue(client2Received.await(1, TimeUnit.SECONDS));
    }

    @Test
    void packetToUnknownAddressIsDropped() throws Exception
    {
        TestEndPoint client = network.createTestEndPoint();
        client.setReceiveHandler((data, from) -> {});
        client.start();

        // Send to address that doesn't exist
        SocketAddress unknownAddr = new InetSocketAddress("127.0.0.1", 59999);
        client.send(ByteBuffer.wrap(new byte[]{1}), unknownAddr);

        assertEquals(1, client.getPacketsDropped());
    }

    @Test
    void packetRoutedToWildcardBoundServer() throws Exception
    {
        // Server binds to wildcard address (0.0.0.0:port)
        SocketAddress wildcardAddr = new InetSocketAddress(17777);
        TestEndPoint server = network.createTestEndPoint(wildcardAddr);
        TestEndPoint client = network.createTestEndPoint();

        CountDownLatch received = new CountDownLatch(1);
        AtomicReference<byte[]> receivedData = new AtomicReference<>();
        AtomicReference<SocketAddress> receivedFrom = new AtomicReference<>();

        server.setReceiveHandler((data, from) ->
        {
            byte[] copy = new byte[data.remaining()];
            data.get(copy);
            receivedData.set(copy);
            receivedFrom.set(from);
            received.countDown();
        });
        client.setReceiveHandler((data, from) -> {});

        server.start();
        client.start();

        // Send to specific address (127.0.0.1:port) - should be routed to wildcard server
        SocketAddress targetAddr = new InetSocketAddress("127.0.0.1", 17777);
        byte[] message = "Hello Server!".getBytes();
        client.send(ByteBuffer.wrap(message), targetAddr);

        assertTrue(received.await(1, TimeUnit.SECONDS), "Server should receive packet via wildcard routing");
        assertArrayEquals(message, receivedData.get());
        assertEquals(client.getLocalAddress(), receivedFrom.get());
    }

    @Test
    void getEndPointFindsWildcardBoundServer()
    {
        // Server binds to wildcard address
        SocketAddress wildcardAddr = new InetSocketAddress(17777);
        TestEndPoint server = network.createTestEndPoint(wildcardAddr);

        // getEndPoint with specific address should find wildcard-bound server
        SocketAddress specificAddr = new InetSocketAddress("127.0.0.1", 17777);
        TestEndPoint found = network.getEndPoint(specificAddr);

        assertNotNull(found, "Should find wildcard-bound server");
        assertEquals(server, found);
    }

    // ========== Network-Wide Conditions ==========

    @Test
    void networkLatencyAppliedToAllPackets() throws Exception
    {
        network.setLatency(Duration.ofMillis(100));

        SocketAddress serverAddr = new InetSocketAddress("127.0.0.1", 17777);
        TestEndPoint server = network.createTestEndPoint(serverAddr);
        TestEndPoint client = network.createTestEndPoint();

        CountDownLatch received = new CountDownLatch(1);
        AtomicReference<Long> receiveTime = new AtomicReference<>();

        server.setReceiveHandler((data, from) ->
        {
            receiveTime.set(System.currentTimeMillis());
            received.countDown();
        });
        client.setReceiveHandler((data, from) -> {});

        server.start();
        client.start();

        long sendTime = System.currentTimeMillis();
        client.send(ByteBuffer.wrap(new byte[]{1}), serverAddr);

        assertTrue(received.await(1, TimeUnit.SECONDS));
        long elapsed = receiveTime.get() - sendTime;

        assertTrue(elapsed >= 90, "Should have ~100ms network latency, got: " + elapsed + "ms");
    }

    @Test
    void networkAndEndPointLatencyStack() throws Exception
    {
        network.setLatency(Duration.ofMillis(50));

        SocketAddress serverAddr = new InetSocketAddress("127.0.0.1", 17777);
        TestEndPoint server = network.createTestEndPoint(serverAddr);
        TestEndPoint client = network.createTestEndPoint();

        // Add per-endpoint latency too
        client.setLatency(Duration.ofMillis(50));

        CountDownLatch received = new CountDownLatch(1);
        AtomicReference<Long> receiveTime = new AtomicReference<>();

        server.setReceiveHandler((data, from) ->
        {
            receiveTime.set(System.currentTimeMillis());
            received.countDown();
        });
        client.setReceiveHandler((data, from) -> {});

        server.start();
        client.start();

        long sendTime = System.currentTimeMillis();
        client.send(ByteBuffer.wrap(new byte[]{1}), serverAddr);

        assertTrue(received.await(1, TimeUnit.SECONDS));
        long elapsed = receiveTime.get() - sendTime;

        // Should have ~100ms total (50ms network + 50ms endpoint)
        assertTrue(elapsed >= 90, "Should have ~100ms total latency, got: " + elapsed + "ms");
    }

    @Test
    void networkPacketLoss() throws Exception
    {
        network.setPacketLossRate(0.5); // 50% loss

        SocketAddress serverAddr = new InetSocketAddress("127.0.0.1", 17777);
        TestEndPoint server = network.createTestEndPoint(serverAddr);
        TestEndPoint client = network.createTestEndPoint();

        AtomicInteger receivedCount = new AtomicInteger(0);

        server.setReceiveHandler((data, from) -> receivedCount.incrementAndGet());
        client.setReceiveHandler((data, from) -> {});

        server.start();
        client.start();

        // Send many packets
        int totalPackets = 100;
        for (int i = 0; i < totalPackets; i++)
        {
            client.send(ByteBuffer.wrap(new byte[]{(byte) i}), serverAddr);
        }

        // Wait for delivery
        Thread.sleep(500);

        // With 50% loss, expect roughly half to arrive
        int received = receivedCount.get();
        assertTrue(received > 20 && received < 80,
                "Expected roughly 50% delivery, got: " + received + "/" + totalPackets);
    }

    @Test
    void networkResetClearsConditions() throws Exception
    {
        network.setLatency(Duration.ofMillis(100));
        network.setPacketLossRate(0.5);

        network.reset();

        assertEquals(Duration.ZERO, network.getMinLatency());
        assertEquals(Duration.ZERO, network.getMaxLatency());
        assertEquals(0.0, network.getPacketLossRate());
    }

    // ========== EndPoint Access ==========

    @Test
    void endPointReturnsNetwork()
    {
        SimulatedEndPoint endPoint = (SimulatedEndPoint) network.createEndPoint();
        assertEquals(network, endPoint.getNetwork());
    }
}
