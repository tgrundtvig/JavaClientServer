package org.abstractica.clientserver.impl.integration;

import org.abstractica.clientserver.Client;
import org.abstractica.clientserver.Server;
import org.abstractica.clientserver.Session;
import org.abstractica.clientserver.impl.client.DefaultClientFactory;
import org.abstractica.clientserver.impl.crypto.Signer;
import org.abstractica.clientserver.impl.serialization.DefaultProtocol;
import org.abstractica.clientserver.impl.session.DefaultServerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration tests for client-server communication.
 */
class EndToEndTest
{
    // ========== Test Protocol ==========

    /**
     * Messages sent from client to server.
     */
    public sealed interface ClientMessage permits
            ClientMessage.Ping,
            ClientMessage.Echo,
            ClientMessage.Login
    {
        record Ping(long timestamp) implements ClientMessage {}
        record Echo(String text) implements ClientMessage {}
        record Login(String username) implements ClientMessage {}
    }

    /**
     * Messages sent from server to client.
     */
    public sealed interface ServerMessage permits
            ServerMessage.Pong,
            ServerMessage.EchoReply,
            ServerMessage.Welcome,
            ServerMessage.Broadcast
    {
        record Pong(long timestamp, long serverTime) implements ServerMessage {}
        record EchoReply(String text) implements ServerMessage {}
        record Welcome(String greeting) implements ServerMessage {}
        record Broadcast(String message) implements ServerMessage {}
    }

    // ========== Test Setup ==========

    private static final int TEST_PORT = 17777;

    private DefaultProtocol protocol;
    private PrivateKey serverPrivateKey;
    private PublicKey serverPublicKey;
    private Server server;
    private Client client;

    @BeforeEach
    void setUp()
    {
        // Build protocol
        protocol = new DefaultProtocol.Builder()
                .clientMessages(ClientMessage.class)
                .serverMessages(ServerMessage.class)
                .build();

        // Generate server keys
        Signer.SigningKeyPair keyPair = Signer.generateKeyPair();
        serverPrivateKey = keyPair.privateKey();
        serverPublicKey = keyPair.publicKey();
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
    void clientConnectsToServer() throws Exception
    {
        // Arrange
        CountDownLatch serverSessionStarted = new CountDownLatch(1);
        CountDownLatch clientConnected = new CountDownLatch(1);

        server = createServer();
        server.onSessionStarted(session -> serverSessionStarted.countDown());
        server.start();

        client = createClient();
        client.onConnected(session -> clientConnected.countDown());

        // Act
        client.connect();

        // Assert
        assertTrue(clientConnected.await(5, TimeUnit.SECONDS), "Client should connect");
        assertTrue(serverSessionStarted.await(5, TimeUnit.SECONDS), "Server should receive session");
        assertNotNull(client.getSession());
        assertEquals(1, server.getSessions().size());
    }

    @Test
    void clientSendsMessageToServer() throws Exception
    {
        // Arrange
        CountDownLatch clientConnected = new CountDownLatch(1);
        CountDownLatch messageReceived = new CountDownLatch(1);
        AtomicReference<ClientMessage.Echo> receivedMessage = new AtomicReference<>();

        server = createServer();
        server.onMessage(ClientMessage.Echo.class, (session, msg) ->
        {
            receivedMessage.set(msg);
            messageReceived.countDown();
        });
        server.start();

        client = createClient();
        client.onConnected(session -> clientConnected.countDown());
        client.connect();

        assertTrue(clientConnected.await(5, TimeUnit.SECONDS));

        // Act
        client.send(new ClientMessage.Echo("Hello, Server!"));

        // Assert
        assertTrue(messageReceived.await(5, TimeUnit.SECONDS), "Server should receive message");
        assertEquals("Hello, Server!", receivedMessage.get().text());
    }

    @Test
    void serverSendsMessageToClient() throws Exception
    {
        // Arrange
        CountDownLatch clientConnected = new CountDownLatch(1);
        CountDownLatch messageReceived = new CountDownLatch(1);
        AtomicReference<ServerMessage.Welcome> receivedMessage = new AtomicReference<>();
        AtomicReference<Session> serverSession = new AtomicReference<>();

        server = createServer();
        server.onSessionStarted(serverSession::set);
        server.start();

        client = createClient();
        client.onConnected(session -> clientConnected.countDown());
        client.onMessage(ServerMessage.Welcome.class, (session, msg) ->
        {
            receivedMessage.set(msg);
            messageReceived.countDown();
        });
        client.connect();

        assertTrue(clientConnected.await(5, TimeUnit.SECONDS));

        // Wait for server to have the session
        Thread.sleep(100);

        // Act
        serverSession.get().send(new ServerMessage.Welcome("Welcome to the server!"));

        // Assert
        assertTrue(messageReceived.await(5, TimeUnit.SECONDS), "Client should receive message");
        assertEquals("Welcome to the server!", receivedMessage.get().greeting());
    }

    @Test
    void pingPongExchange() throws Exception
    {
        // Arrange
        CountDownLatch clientConnected = new CountDownLatch(1);
        CountDownLatch pongReceived = new CountDownLatch(1);
        AtomicReference<ServerMessage.Pong> receivedPong = new AtomicReference<>();

        server = createServer();
        server.onMessage(ClientMessage.Ping.class, (session, ping) ->
        {
            session.send(new ServerMessage.Pong(ping.timestamp(), System.currentTimeMillis()));
        });
        server.start();

        client = createClient();
        client.onConnected(session -> clientConnected.countDown());
        client.onMessage(ServerMessage.Pong.class, (session, pong) ->
        {
            receivedPong.set(pong);
            pongReceived.countDown();
        });
        client.connect();

        assertTrue(clientConnected.await(5, TimeUnit.SECONDS));

        // Act
        long pingTime = System.currentTimeMillis();
        client.send(new ClientMessage.Ping(pingTime));

        // Assert
        assertTrue(pongReceived.await(5, TimeUnit.SECONDS), "Should receive pong");
        assertEquals(pingTime, receivedPong.get().timestamp());
        assertTrue(receivedPong.get().serverTime() >= pingTime);
    }

    @Test
    void multipleMessagesInSequence() throws Exception
    {
        // Arrange
        int messageCount = 10;
        CountDownLatch clientConnected = new CountDownLatch(1);
        CountDownLatch allMessagesReceived = new CountDownLatch(messageCount);

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
            allMessagesReceived.countDown();
        });
        client.connect();

        assertTrue(clientConnected.await(5, TimeUnit.SECONDS));

        // Act
        for (int i = 0; i < messageCount; i++)
        {
            client.send(new ClientMessage.Echo("Message " + i));
        }

        // Assert
        assertTrue(allMessagesReceived.await(10, TimeUnit.SECONDS),
                "All messages should be echoed back");
    }

    @Test
    void serverBroadcastsToAllClients() throws Exception
    {
        // Arrange
        CountDownLatch client1Connected = new CountDownLatch(1);
        CountDownLatch client2Connected = new CountDownLatch(1);
        CountDownLatch bothReceived = new CountDownLatch(2);

        server = createServer();
        server.start();

        Client client1 = createClient();
        client1.onConnected(session -> client1Connected.countDown());
        client1.onMessage(ServerMessage.Broadcast.class, (session, msg) -> bothReceived.countDown());

        Client client2 = createClient();
        client2.onConnected(session -> client2Connected.countDown());
        client2.onMessage(ServerMessage.Broadcast.class, (session, msg) -> bothReceived.countDown());

        client1.connect();
        client2.connect();

        assertTrue(client1Connected.await(5, TimeUnit.SECONDS));
        assertTrue(client2Connected.await(5, TimeUnit.SECONDS));

        // Wait for both sessions to be established
        Thread.sleep(200);
        assertEquals(2, server.getSessions().size());

        // Act
        server.broadcast(new ServerMessage.Broadcast("Hello everyone!"));

        // Assert
        assertTrue(bothReceived.await(5, TimeUnit.SECONDS),
                "Both clients should receive broadcast");

        // Cleanup
        client1.close();
        client2.close();
    }

    @Test
    void clientDisconnectsGracefully() throws Exception
    {
        // Arrange
        CountDownLatch clientConnected = new CountDownLatch(1);
        CountDownLatch serverSessionDisconnected = new CountDownLatch(1);

        server = createServer();
        server.onSessionDisconnected((session, reason) -> serverSessionDisconnected.countDown());
        server.start();

        client = createClient();
        client.onConnected(session -> clientConnected.countDown());
        client.connect();

        assertTrue(clientConnected.await(5, TimeUnit.SECONDS));

        // Act
        client.disconnect();

        // Assert
        assertTrue(serverSessionDisconnected.await(5, TimeUnit.SECONDS),
                "Server should be notified of disconnect");
    }

    @Test
    void sessionAttachmentWorks() throws Exception
    {
        // Arrange
        CountDownLatch clientConnected = new CountDownLatch(1);
        CountDownLatch loginProcessed = new CountDownLatch(1);
        AtomicReference<String> storedUsername = new AtomicReference<>();

        server = createServer();
        server.onMessage(ClientMessage.Login.class, (session, msg) ->
        {
            session.setAttachment(msg.username());
            session.send(new ServerMessage.Welcome("Hello, " + msg.username()));
        });
        server.onMessage(ClientMessage.Echo.class, (session, msg) ->
        {
            String username = (String) session.getAttachment();
            session.send(new ServerMessage.EchoReply(username + " says: " + msg.text()));
        });
        server.start();

        client = createClient();
        client.onConnected(session -> clientConnected.countDown());
        client.onMessage(ServerMessage.EchoReply.class, (session, msg) ->
        {
            storedUsername.set(msg.text());
            loginProcessed.countDown();
        });
        client.connect();

        assertTrue(clientConnected.await(5, TimeUnit.SECONDS));

        // Act
        client.send(new ClientMessage.Login("Alice"));
        Thread.sleep(100); // Wait for login to process
        client.send(new ClientMessage.Echo("Hello!"));

        // Assert
        assertTrue(loginProcessed.await(5, TimeUnit.SECONDS));
        assertEquals("Alice says: Hello!", storedUsername.get());
    }

    // ========== Helper Methods ==========

    private Server createServer()
    {
        return new DefaultServerFactory().builder()
                .port(TEST_PORT)
                .protocol(protocol)
                .privateKey(serverPrivateKey)
                .build();
    }

    private Client createClient()
    {
        return new DefaultClientFactory().builder()
                .serverAddress("localhost", TEST_PORT)
                .protocol(protocol)
                .serverPublicKey(serverPublicKey)
                .build();
    }
}
