package org.abstractica.demo.server;

import org.abstractica.clientserver.Delivery;
import org.abstractica.clientserver.Protocol;
import org.abstractica.clientserver.Server;
import org.abstractica.clientserver.Session;
import org.abstractica.clientserver.impl.crypto.Signer;
import org.abstractica.clientserver.impl.serialization.DefaultProtocol;
import org.abstractica.clientserver.impl.session.DefaultServerFactory;
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
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Demo server application demonstrating library features.
 *
 * <p>Features demonstrated:</p>
 * <ul>
 *   <li>Protocol creation with sealed interfaces</li>
 *   <li>Server lifecycle and configuration</li>
 *   <li>Message handlers for each message type</li>
 *   <li>Session attachments for player state</li>
 *   <li>Reliable and unreliable message delivery</li>
 *   <li>Broadcast to all clients</li>
 *   <li>Session lifecycle callbacks</li>
 * </ul>
 */
public class DemoServer
{
    private static final Logger LOG = LoggerFactory.getLogger(DemoServer.class);
    private static final int DEFAULT_PORT = 7777;
    private static final String PUBLIC_KEY_FILE = "server-public-key.txt";

    private final Server server;
    private final int port;
    private boolean gameStarted = false;

    public DemoServer(int port, Protocol protocol, PrivateKey privateKey)
    {
        this.port = port;
        this.server = new DefaultServerFactory().builder()
                .port(port)
                .protocol(protocol)
                .privateKey(privateKey)
                .build();

        registerMessageHandlers();
        registerLifecycleCallbacks();
    }

    private void registerMessageHandlers()
    {
        server.onMessage(ClientMessage.Join.class, this::handleJoin);
        server.onMessage(ClientMessage.Ready.class, this::handleReady);
        server.onMessage(ClientMessage.Move.class, this::handleMove);
        server.onMessage(ClientMessage.Chat.class, this::handleChat);

        server.onError((session, message, exception) ->
        {
            LOG.error("Error handling message: session={}, message={}", session.getId(), message, exception);
        });
    }

    private void registerLifecycleCallbacks()
    {
        server.onSessionStarted(session ->
        {
            LOG.info("New session started: {}", session.getId());
            session.setAttachment(new PlayerState(session.getId()));
        });

        server.onSessionDisconnected((session, reason) ->
        {
            PlayerState state = getPlayerState(session);
            if (state != null && state.hasJoined())
            {
                LOG.info("Player disconnected: {} ({})", state.getName(), reason);
                server.broadcast(new ServerMessage.PlayerLeft(state.getPlayerId()));
            }
            else
            {
                LOG.info("Session disconnected: {} ({})", session.getId(), reason);
            }
        });

        server.onSessionReconnected(session ->
        {
            PlayerState state = getPlayerState(session);
            if (state != null && state.hasJoined())
            {
                LOG.info("Player reconnected: {}", state.getName());
            }
            else
            {
                LOG.info("Session reconnected: {}", session.getId());
            }
        });

        server.onSessionExpired(session ->
        {
            PlayerState state = getPlayerState(session);
            if (state != null && state.hasJoined())
            {
                LOG.info("Player session expired: {}", state.getName());
                server.broadcast(new ServerMessage.PlayerLeft(state.getPlayerId()));
            }
            else
            {
                LOG.info("Session expired: {}", session.getId());
            }
        });
    }

    private void handleJoin(Session session, ClientMessage.Join join)
    {
        PlayerState state = getPlayerState(session);
        if (state == null)
        {
            LOG.warn("No player state for session: {}", session.getId());
            return;
        }

        if (state.hasJoined())
        {
            LOG.warn("Player {} tried to join again", state.getName());
            return;
        }

        state.setName(join.playerName());
        LOG.info("Player joined: {} (session {})", join.playerName(), session.getId());

        // Send welcome with current player list
        List<Player> players = getCurrentPlayers();
        session.send(new ServerMessage.Welcome(state.getPlayerId(), players));

        // Broadcast new player to everyone else
        Player newPlayer = new Player(state.getPlayerId(), state.getName(), state.getX(), state.getY());
        for (Session other : server.getSessions())
        {
            if (!other.getId().equals(session.getId()))
            {
                PlayerState otherState = getPlayerState(other);
                if (otherState != null && otherState.hasJoined())
                {
                    other.send(new ServerMessage.PlayerJoined(newPlayer));
                }
            }
        }
    }

    private void handleReady(Session session, ClientMessage.Ready ready)
    {
        PlayerState state = getPlayerState(session);
        if (state == null || !state.hasJoined())
        {
            LOG.warn("Ready received from non-joined session: {}", session.getId());
            return;
        }

        state.setReady(true);
        LOG.info("Player ready: {}", state.getName());

        checkGameStart();
    }

    private void handleMove(Session session, ClientMessage.Move move)
    {
        PlayerState state = getPlayerState(session);
        if (state == null || !state.hasJoined())
        {
            return;
        }

        state.setPosition(move.x(), move.y());

        // Broadcast state update to all players (unreliable for position updates)
        GameState gameState = new GameState(getCurrentPlayers());
        server.broadcast(new ServerMessage.StateUpdate(gameState), Delivery.UNRELIABLE);
    }

    private void handleChat(Session session, ClientMessage.Chat chat)
    {
        PlayerState state = getPlayerState(session);
        if (state == null || !state.hasJoined())
        {
            return;
        }

        LOG.info("[Chat] {}: {}", state.getName(), chat.text());

        // Broadcast chat to all players (reliable)
        server.broadcast(new ServerMessage.ChatBroadcast(state.getPlayerId(), chat.text()));
    }

    private void checkGameStart()
    {
        if (gameStarted)
        {
            return;
        }

        List<Player> players = getCurrentPlayers();
        if (players.isEmpty())
        {
            return;
        }

        boolean allReady = true;
        for (Session session : server.getSessions())
        {
            PlayerState state = getPlayerState(session);
            if (state != null && state.hasJoined() && !state.isReady())
            {
                allReady = false;
                break;
            }
        }

        if (allReady)
        {
            gameStarted = true;
            GameState initialState = new GameState(players);
            LOG.info("Game starting with {} players", players.size());
            server.broadcast(new ServerMessage.GameStarted(initialState));
        }
    }

    private List<Player> getCurrentPlayers()
    {
        List<Player> players = new ArrayList<>();
        for (Session session : server.getSessions())
        {
            PlayerState state = getPlayerState(session);
            if (state != null && state.hasJoined())
            {
                players.add(new Player(state.getPlayerId(), state.getName(), state.getX(), state.getY()));
            }
        }
        return players;
    }

    private PlayerState getPlayerState(Session session)
    {
        return session.getAttachment()
                .filter(PlayerState.class::isInstance)
                .map(PlayerState.class::cast)
                .orElse(null);
    }

    public void start()
    {
        server.start();
        LOG.info("Demo server started on port {}", port);
    }

    public void stop()
    {
        server.close();
        LOG.info("Demo server stopped");
    }

    public void runCommandLoop()
    {
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        System.out.println("Server commands: list, kick <name>, quit");

        try
        {
            String line;
            while ((line = reader.readLine()) != null)
            {
                String[] parts = line.trim().split("\\s+", 2);
                String command = parts[0].toLowerCase();

                switch (command)
                {
                    case "list" -> listPlayers();
                    case "kick" ->
                    {
                        if (parts.length > 1)
                        {
                            kickPlayer(parts[1]);
                        }
                        else
                        {
                            System.out.println("Usage: kick <player name>");
                        }
                    }
                    case "quit", "exit", "q" ->
                    {
                        System.out.println("Shutting down...");
                        return;
                    }
                    case "" ->
                    {
                        // Ignore empty input
                    }
                    default -> System.out.println("Unknown command: " + command);
                }
            }
        }
        catch (IOException e)
        {
            LOG.error("Error reading console input", e);
        }
    }

    private void listPlayers()
    {
        List<Player> players = getCurrentPlayers();
        if (players.isEmpty())
        {
            System.out.println("No players connected");
        }
        else
        {
            System.out.println("Connected players:");
            for (Player player : players)
            {
                System.out.printf("  %s (%s) at (%d, %d)%n",
                        player.name(), player.id(), player.x(), player.y());
            }
        }
    }

    private void kickPlayer(String name)
    {
        for (Session session : server.getSessions())
        {
            PlayerState state = getPlayerState(session);
            if (state != null && state.hasJoined() && state.getName().equalsIgnoreCase(name))
            {
                LOG.info("Kicking player: {}", state.getName());
                session.close("Kicked by server");
                return;
            }
        }
        System.out.println("Player not found: " + name);
    }

    public static void main(String[] args)
    {
        int port = DEFAULT_PORT;
        if (args.length > 0)
        {
            try
            {
                port = Integer.parseInt(args[0]);
            }
            catch (NumberFormatException e)
            {
                System.err.println("Invalid port number: " + args[0]);
                System.exit(1);
            }
        }

        // Build protocol
        Protocol protocol = new DefaultProtocol.Builder()
                .clientMessages(ClientMessage.class)
                .serverMessages(ServerMessage.class)
                .build();

        // Generate server keys
        Signer.SigningKeyPair keyPair = Signer.generateKeyPair();
        PrivateKey privateKey = keyPair.privateKey();
        PublicKey publicKey = keyPair.publicKey();

        // Save public key for clients
        savePublicKey(publicKey);

        // Create and start server
        DemoServer demoServer = new DemoServer(port, protocol, privateKey);
        demoServer.start();

        System.out.println("Public key saved to: " + PUBLIC_KEY_FILE);
        System.out.println("Share this file with clients to connect.");
        System.out.println();

        // Run command loop
        demoServer.runCommandLoop();

        // Cleanup
        demoServer.stop();
    }

    private static void savePublicKey(PublicKey publicKey)
    {
        try
        {
            String encoded = Base64.getEncoder().encodeToString(publicKey.getEncoded());
            Files.writeString(Path.of(PUBLIC_KEY_FILE), encoded);
        }
        catch (IOException e)
        {
            LOG.error("Failed to save public key", e);
        }
    }
}
