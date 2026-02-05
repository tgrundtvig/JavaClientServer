package org.abstractica.clientserver.impl.integration;

import org.abstractica.clientserver.Client;
import org.abstractica.clientserver.Server;
import org.abstractica.clientserver.Session;
import org.abstractica.clientserver.impl.client.DefaultClientFactory;
import org.abstractica.clientserver.impl.crypto.Signer;
import org.abstractica.clientserver.impl.serialization.DefaultProtocol;
import org.abstractica.clientserver.impl.session.DefaultServerFactory;
import org.abstractica.clientserver.impl.transport.SimulatedTransport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests using SimulatedTransport to test network conditions.
 */
class SimulatedNetworkTest
{
    // ========== Test Protocol ==========

    public sealed interface ClientMessage permits ClientMessage.Echo
    {
        record Echo(String text) implements ClientMessage {}
    }

    public sealed interface ServerMessage permits ServerMessage.EchoReply
    {
        record EchoReply(String text) implements ServerMessage {}
    }

    // ========== Test Setup ==========

    private static final int SERVER_PORT = 17777;

    private DefaultProtocol protocol;
    private PrivateKey serverPrivateKey;
    private PublicKey serverPublicKey;
    private SimulatedTransport serverTransport;
    private SimulatedTransport clientTransport;
    private Server server;
    private Client client;

    @BeforeEach
    void setUp()
    {
        protocol = new DefaultProtocol.Builder()
                .clientMessages(ClientMessage.class)
                .serverMessages(ServerMessage.class)
                .build();

        Signer.SigningKeyPair keyPair = Signer.generateKeyPair();
        serverPrivateKey = keyPair.privateKey();
        serverPublicKey = keyPair.publicKey();

        // Create simulated transports
        serverTransport = new SimulatedTransport(new InetSocketAddress("127.0.0.1", SERVER_PORT));
        clientTransport = new SimulatedTransport();

        // Connect them
        serverTransport.connectTo(clientTransport);
        clientTransport.connectTo(serverTransport);
    }

    @AfterEach
    void tearDown()
    {
        if (client != null)
        {
            client.close();
        }
        if (server != null)
        {
            server.close();
        }
    }

    // ========== Tests ==========

    @Test
    void connectionWithSimulatedTransport() throws Exception
    {
        CountDownLatch clientConnected = new CountDownLatch(1);
        CountDownLatch serverSessionStarted = new CountDownLatch(1);

        server = createServer();
        server.onSessionStarted(session -> serverSessionStarted.countDown());
        server.start();

        client = createClient();
        client.onConnected(session -> clientConnected.countDown());
        client.connect();

        assertTrue(clientConnected.await(2, TimeUnit.SECONDS), "Client should connect");
        assertTrue(serverSessionStarted.await(2, TimeUnit.SECONDS), "Server should receive session");
    }

    @Test
    void messageExchangeWithLatency() throws Exception
    {
        // Add 50ms latency each way
        serverTransport.setLatency(Duration.ofMillis(50));
        clientTransport.setLatency(Duration.ofMillis(50));

        CountDownLatch clientConnected = new CountDownLatch(1);
        CountDownLatch messageReceived = new CountDownLatch(1);
        AtomicReference<String> receivedText = new AtomicReference<>();

        server = createServer();
        server.onMessage(ClientMessage.Echo.class, (session, msg) ->
        {
            session.send(new ServerMessage.EchoReply(msg.text()));
        });
        server.start();

        client = createClient();
        client.onConnected(session -> clientConnected.countDown());
        client.onMessage(ServerMessage.EchoReply.class, (session, msg) ->
        {
            receivedText.set(msg.text());
            messageReceived.countDown();
        });
        client.connect();

        assertTrue(clientConnected.await(2, TimeUnit.SECONDS));

        long start = System.currentTimeMillis();
        client.send(new ClientMessage.Echo("Hello with latency!"));

        assertTrue(messageReceived.await(2, TimeUnit.SECONDS));
        long elapsed = System.currentTimeMillis() - start;

        assertEquals("Hello with latency!", receivedText.get());
        // Should take at least 100ms round trip (50ms each way)
        assertTrue(elapsed >= 80, "Should have ~100ms RTT, got: " + elapsed + "ms");
    }

    @Test
    void reliabilityHandlesPacketLoss() throws Exception
    {
        // 10% packet loss in both directions - lower rate for test stability
        serverTransport.setPacketLossRate(0.1);
        clientTransport.setPacketLossRate(0.1);

        CountDownLatch clientConnected = new CountDownLatch(1);
        AtomicInteger messagesReceived = new AtomicInteger(0);
        int messagesToSend = 10;
        CountDownLatch allReceived = new CountDownLatch(messagesToSend);

        server = createServer();
        server.onMessage(ClientMessage.Echo.class, (session, msg) ->
        {
            session.send(new ServerMessage.EchoReply(msg.text()));
        });
        server.start();

        client = createClient();
        client.onConnected(session -> clientConnected.countDown());
        client.onMessage(ServerMessage.EchoReply.class, (session, msg) ->
        {
            messagesReceived.incrementAndGet();
            allReceived.countDown();
        });
        client.connect();

        assertTrue(clientConnected.await(5, TimeUnit.SECONDS));

        // Send messages with small delay to allow retransmission time
        for (int i = 0; i < messagesToSend; i++)
        {
            client.send(new ClientMessage.Echo("Message " + i));
            Thread.sleep(10); // Small delay between sends
        }

        // With reliability layer, all messages should eventually be delivered
        // despite packet loss (due to retransmission)
        assertTrue(allReceived.await(60, TimeUnit.SECONDS),
                "All messages should be delivered despite packet loss. Received: " + messagesReceived.get());

        assertEquals(messagesToSend, messagesReceived.get());
    }

    @Test
    void handshakeSucceedsWithZeroPacketLoss() throws Exception
    {
        CountDownLatch clientConnected = new CountDownLatch(1);

        server = createServer();
        server.start();

        client = createClient();
        client.onConnected(session -> clientConnected.countDown());
        client.connect();

        assertTrue(clientConnected.await(2, TimeUnit.SECONDS),
                "Client should connect with simulated transport");
    }

    @Test
    void handshakeRetryOnClientHelloLoss() throws Exception
    {
        // Drop the first ClientHello - client should retry
        clientTransport.dropNextPacket();

        CountDownLatch clientConnected = new CountDownLatch(1);

        server = createServer();
        server.start();

        client = createClient();
        client.onConnected(session -> clientConnected.countDown());
        client.connect();

        // Should connect after retry (retry interval is 1 second)
        assertTrue(clientConnected.await(5, TimeUnit.SECONDS),
                "Client should connect after ClientHello retry");
    }

    @Test
    void handshakeRetryOnServerHelloLoss() throws Exception
    {
        // Drop the first ServerHello - client should retry ClientHello
        // which causes server to regenerate keys and resend ServerHello
        serverTransport.dropNextPacket();

        CountDownLatch clientConnected = new CountDownLatch(1);

        server = createServer();
        server.start();

        client = createClient();
        client.onConnected(session -> clientConnected.countDown());
        client.connect();

        // Should connect after retry
        assertTrue(clientConnected.await(5, TimeUnit.SECONDS),
                "Client should connect after ServerHello loss (via ClientHello retry)");
    }

    @Test
    void handshakeRetryOnConnectLoss() throws Exception
    {
        CountDownLatch clientConnected = new CountDownLatch(1);

        server = createServer();
        server.start();

        client = createClient();
        client.onConnected(session -> clientConnected.countDown());

        // Start connection, then drop the Connect packet after ServerHello is received
        // We need to delay the drop until after ClientHello and ServerHello exchange
        client.connect();

        // Wait a bit for ClientHello/ServerHello exchange, then drop the Connect
        Thread.sleep(100);
        clientTransport.dropNextPacket();

        // Should connect after retry
        assertTrue(clientConnected.await(5, TimeUnit.SECONDS),
                "Client should connect after Connect retry");
    }

    @Test
    void handshakeRetryOnAcceptLoss() throws Exception
    {
        CountDownLatch clientConnected = new CountDownLatch(1);
        CountDownLatch serverSessionStarted = new CountDownLatch(1);

        server = createServer();
        server.onSessionStarted(session -> serverSessionStarted.countDown());
        server.start();

        client = createClient();
        client.onConnected(session -> clientConnected.countDown());

        // Start connection, drop the Accept after Connect is received
        client.connect();

        // Wait for server session to be created (means Connect was processed)
        assertTrue(serverSessionStarted.await(2, TimeUnit.SECONDS),
                "Server should create session");

        // Now drop the Accept (it may already be in flight, so drop next outgoing)
        serverTransport.dropNextPacket();

        // Client should retry Connect, server should resend Accept
        assertTrue(clientConnected.await(5, TimeUnit.SECONDS),
                "Client should connect after Accept loss (via Connect retry)");
    }

    @Test
    void connectionStatistics() throws Exception
    {
        CountDownLatch clientConnected = new CountDownLatch(1);
        CountDownLatch messageExchanged = new CountDownLatch(1);

        server = createServer();
        server.onMessage(ClientMessage.Echo.class, (session, msg) ->
        {
            session.send(new ServerMessage.EchoReply(msg.text()));
        });
        server.start();

        client = createClient();
        client.onConnected(session -> clientConnected.countDown());
        client.onMessage(ServerMessage.EchoReply.class, (session, msg) -> messageExchanged.countDown());
        client.connect();

        assertTrue(clientConnected.await(2, TimeUnit.SECONDS));

        // Exchange a message
        client.send(new ClientMessage.Echo("Test"));
        assertTrue(messageExchanged.await(2, TimeUnit.SECONDS));

        // Check transport statistics
        assertTrue(serverTransport.getPacketsSent() > 0);
        assertTrue(clientTransport.getPacketsSent() > 0);
        assertTrue(serverTransport.getPacketsDelivered() > 0);
        assertTrue(clientTransport.getPacketsDelivered() > 0);
    }

    // ========== Helper Methods ==========

    private Server createServer()
    {
        DefaultServerFactory.DefaultBuilder builder =
                (DefaultServerFactory.DefaultBuilder) new DefaultServerFactory().builder();
        builder.port(SERVER_PORT);
        builder.protocol(protocol);
        builder.privateKey(serverPrivateKey);
        builder.transport(serverTransport);
        return builder.build();
    }

    private Client createClient()
    {
        DefaultClientFactory.DefaultBuilder builder =
                (DefaultClientFactory.DefaultBuilder) new DefaultClientFactory().builder();
        builder.serverAddress("127.0.0.1", SERVER_PORT);
        builder.protocol(protocol);
        builder.serverPublicKey(serverPublicKey);
        builder.transport(clientTransport);
        return builder.build();
    }
}
