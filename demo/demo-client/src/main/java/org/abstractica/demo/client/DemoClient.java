package org.abstractica.demo.client;

import org.abstractica.clientserver.Client;
import org.abstractica.clientserver.Delivery;
import org.abstractica.clientserver.DisconnectReason;
import org.abstractica.clientserver.Network;
import org.abstractica.clientserver.Protocol;
import org.abstractica.clientserver.impl.client.DefaultClientFactory;
import org.abstractica.clientserver.impl.serialization.DefaultProtocol;
import org.abstractica.clientserver.impl.transport.LossyNetwork;
import org.abstractica.clientserver.impl.transport.UdpNetwork;
import org.abstractica.demo.protocol.ClientMessage;
import org.abstractica.demo.protocol.GameState;
import org.abstractica.demo.protocol.Player;
import org.abstractica.demo.protocol.ServerMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.util.Base64;

/**
 * Demo client application demonstrating library features.
 *
 * <p>Features demonstrated:</p>
 * <ul>
 *   <li>Protocol creation with sealed interfaces</li>
 *   <li>Client configuration and connection</li>
 *   <li>Message handlers for each server message type</li>
 *   <li>Connection lifecycle callbacks</li>
 *   <li>Reliable and unreliable message delivery</li>
 *   <li>Reconnection handling</li>
 * </ul>
 */
public class DemoClient
{
    private static final Logger LOG = LoggerFactory.getLogger(DemoClient.class);
    private static final String DEFAULT_HOST = "localhost";
    private static final int DEFAULT_PORT = 7777;
    private static final String PUBLIC_KEY_FILE = "server-public-key.txt";

    private final Client client;
    private String playerId;
    private boolean connected = false;

    public DemoClient(String host, int port, Protocol protocol, PublicKey serverPublicKey, Network network)
    {
        this.client = new DefaultClientFactory().builder()
                .serverAddress(host, port)
                .protocol(protocol)
                .serverPublicKey(serverPublicKey)
                .network(network)
                .build();

        registerMessageHandlers();
        registerLifecycleCallbacks();
    }

    private void registerMessageHandlers()
    {
        client.onMessage(ServerMessage.Welcome.class, this::handleWelcome);
        client.onMessage(ServerMessage.PlayerJoined.class, this::handlePlayerJoined);
        client.onMessage(ServerMessage.PlayerLeft.class, this::handlePlayerLeft);
        client.onMessage(ServerMessage.GameStarted.class, this::handleGameStarted);
        client.onMessage(ServerMessage.StateUpdate.class, this::handleStateUpdate);
        client.onMessage(ServerMessage.ChatBroadcast.class, this::handleChatBroadcast);

        client.onError((session, message, exception) ->
        {
            LOG.error("Error handling message: {}", message, exception);
        });
    }

    private void registerLifecycleCallbacks()
    {
        client.onConnected(session ->
        {
            connected = true;
            System.out.println("Connected to server!");
            System.out.println("Commands: join <name>, ready, move <x> <y>, chat <text>, quit");
        });

        client.onConnectionFailed(reason ->
        {
            connected = false;
            System.out.println("Connection failed: " + formatReason(reason));
        });

        client.onDisconnected((session, reason) ->
        {
            connected = false;
            System.out.println("Disconnected: " + formatReason(reason));
        });

        client.onReconnected(session ->
        {
            connected = true;
            System.out.println("Reconnected to server!");
        });
    }

    private void handleWelcome(org.abstractica.clientserver.Session session, ServerMessage.Welcome welcome)
    {
        playerId = welcome.playerId();
        System.out.println("Welcome! Your player ID: " + playerId);
        if (!welcome.players().isEmpty())
        {
            System.out.println("Current players:");
            for (Player player : welcome.players())
            {
                System.out.printf("  %s at (%d, %d)%n", player.name(), player.x(), player.y());
            }
        }
    }

    private void handlePlayerJoined(org.abstractica.clientserver.Session session, ServerMessage.PlayerJoined msg)
    {
        System.out.printf("Player joined: %s%n", msg.player().name());
    }

    private void handlePlayerLeft(org.abstractica.clientserver.Session session, ServerMessage.PlayerLeft msg)
    {
        System.out.printf("Player left: %s%n", msg.playerId());
    }

    private void handleGameStarted(org.abstractica.clientserver.Session session, ServerMessage.GameStarted msg)
    {
        System.out.println("=== Game Started! ===");
        printGameState(msg.initial());
    }

    private void handleStateUpdate(org.abstractica.clientserver.Session session, ServerMessage.StateUpdate msg)
    {
        printGameState(msg.state());
    }

    private void handleChatBroadcast(org.abstractica.clientserver.Session session, ServerMessage.ChatBroadcast msg)
    {
        System.out.printf("[Chat] %s: %s%n", msg.playerId(), msg.text());
    }

    private void printGameState(GameState state)
    {
        System.out.println("Players:");
        for (Player player : state.players())
        {
            String marker = player.id().equals(playerId) ? " (you)" : "";
            System.out.printf("  %s%s at (%d, %d)%n", player.name(), marker, player.x(), player.y());
        }
    }

    private String formatReason(DisconnectReason reason)
    {
        return switch (reason)
        {
            case DisconnectReason.NetworkError e -> "Network error: " + e.cause().getMessage();
            case DisconnectReason.Timeout ignored -> "Connection timed out";
            case DisconnectReason.KickedByServer k -> "Kicked: " + k.message();
            case DisconnectReason.ProtocolError p -> "Protocol error: " + p.details();
            case DisconnectReason.ServerShutdown ignored -> "Server shutdown";
        };
    }

    public void connect()
    {
        System.out.println("Connecting to server...");
        client.connect();
    }

    public void runCommandLoop()
    {
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));

        try
        {
            String line;
            while ((line = reader.readLine()) != null)
            {
                String[] parts = line.trim().split("\\s+", 2);
                String command = parts[0].toLowerCase();

                switch (command)
                {
                    case "join" ->
                    {
                        if (!connected)
                        {
                            System.out.println("Not connected to server");
                        }
                        else if (parts.length > 1)
                        {
                            client.send(new ClientMessage.Join(parts[1]));
                        }
                        else
                        {
                            System.out.println("Usage: join <name>");
                        }
                    }
                    case "ready" ->
                    {
                        if (!connected)
                        {
                            System.out.println("Not connected to server");
                        }
                        else
                        {
                            client.send(new ClientMessage.Ready());
                            System.out.println("Ready signal sent");
                        }
                    }
                    case "move" ->
                    {
                        if (!connected)
                        {
                            System.out.println("Not connected to server");
                        }
                        else if (parts.length > 1)
                        {
                            handleMoveCommand(parts[1]);
                        }
                        else
                        {
                            System.out.println("Usage: move <x> <y>");
                        }
                    }
                    case "chat" ->
                    {
                        if (!connected)
                        {
                            System.out.println("Not connected to server");
                        }
                        else if (parts.length > 1)
                        {
                            client.send(new ClientMessage.Chat(parts[1]));
                        }
                        else
                        {
                            System.out.println("Usage: chat <text>");
                        }
                    }
                    case "quit", "exit", "q" ->
                    {
                        System.out.println("Disconnecting...");
                        return;
                    }
                    case "help" ->
                    {
                        System.out.println("Commands:");
                        System.out.println("  join <name>    - Join the game with the given name");
                        System.out.println("  ready          - Signal you are ready to start");
                        System.out.println("  move <x> <y>   - Move to position (x, y)");
                        System.out.println("  chat <text>    - Send a chat message");
                        System.out.println("  quit           - Disconnect and exit");
                    }
                    case "" ->
                    {
                        // Ignore empty input
                    }
                    default -> System.out.println("Unknown command: " + command + " (type 'help' for commands)");
                }
            }
        }
        catch (IOException e)
        {
            LOG.error("Error reading console input", e);
        }
    }

    private void handleMoveCommand(String args)
    {
        String[] coords = args.trim().split("\\s+");
        if (coords.length != 2)
        {
            System.out.println("Usage: move <x> <y>");
            return;
        }

        try
        {
            int x = Integer.parseInt(coords[0]);
            int y = Integer.parseInt(coords[1]);
            client.send(new ClientMessage.Move(x, y), Delivery.UNRELIABLE);
        }
        catch (NumberFormatException e)
        {
            System.out.println("Invalid coordinates. Usage: move <x> <y>");
        }
    }

    public void disconnect()
    {
        client.close();
    }

    public static void main(String[] args)
    {
        String host = DEFAULT_HOST;
        int port = DEFAULT_PORT;
        String keyFile = PUBLIC_KEY_FILE;
        boolean lossy = false;

        // Parse arguments
        for (int i = 0; i < args.length; i++)
        {
            switch (args[i])
            {
                case "-h", "--host" ->
                {
                    if (i + 1 < args.length)
                    {
                        host = args[++i];
                    }
                }
                case "-p", "--port" ->
                {
                    if (i + 1 < args.length)
                    {
                        try
                        {
                            port = Integer.parseInt(args[++i]);
                        }
                        catch (NumberFormatException e)
                        {
                            System.err.println("Invalid port number");
                            System.exit(1);
                        }
                    }
                }
                case "-k", "--key" ->
                {
                    if (i + 1 < args.length)
                    {
                        keyFile = args[++i];
                    }
                }
                case "--lossy" -> lossy = true;
                case "--help" ->
                {
                    System.out.println("Usage: demo-client [options]");
                    System.out.println("Options:");
                    System.out.println("  -h, --host <host>  Server host (default: localhost)");
                    System.out.println("  -p, --port <port>  Server port (default: 7777)");
                    System.out.println("  -k, --key <file>   Server public key file (default: server-public-key.txt)");
                    System.out.println("  --lossy            Simulate bad network (30% loss, 1000ms delay)");
                    System.exit(0);
                }
            }
        }

        // Load server public key
        PublicKey serverPublicKey = loadPublicKey(keyFile);
        if (serverPublicKey == null)
        {
            System.err.println("Failed to load server public key from: " + keyFile);
            System.err.println("Make sure the server is running and the key file exists.");
            System.exit(1);
        }

        // Build protocol
        Protocol protocol = new DefaultProtocol.Builder()
                .clientMessages(ClientMessage.class)
                .serverMessages(ServerMessage.class)
                .build();

        // Create network (lossy or normal)
        Network network = new UdpNetwork();
        if (lossy)
        {
            network = new LossyNetwork(network, 0.3, Duration.ofMillis(1000));
            System.out.println("*** LOSSY MODE: 30% packet loss, 1000ms delay ***");
        }

        // Create and connect client
        DemoClient demoClient = new DemoClient(host, port, protocol, serverPublicKey, network);
        demoClient.connect();

        // Run command loop
        demoClient.runCommandLoop();

        // Cleanup
        demoClient.disconnect();
    }

    private static PublicKey loadPublicKey(String filename)
    {
        try
        {
            String encoded = Files.readString(Path.of(filename)).trim();
            byte[] keyBytes = Base64.getDecoder().decode(encoded);
            X509EncodedKeySpec spec = new X509EncodedKeySpec(keyBytes);
            KeyFactory keyFactory = KeyFactory.getInstance("Ed25519");
            return keyFactory.generatePublic(spec);
        }
        catch (Exception e)
        {
            LOG.debug("Failed to load public key: {}", e.getMessage());
            return null;
        }
    }
}
