package org.abstractica.demo.protocol;

import java.util.List;

/**
 * Messages sent from server to client.
 */
public sealed interface ServerMessage permits
        ServerMessage.Welcome,
        ServerMessage.PlayerJoined,
        ServerMessage.PlayerLeft,
        ServerMessage.GameStarted,
        ServerMessage.StateUpdate,
        ServerMessage.ChatBroadcast
{
    /**
     * Server welcomes a new player with their assigned ID and current player list.
     *
     * @param playerId the unique ID assigned to the player
     * @param players  list of all currently connected players
     */
    record Welcome(String playerId, List<Player> players) implements ServerMessage
    {
    }

    /**
     * Notifies all clients that a new player joined.
     *
     * @param player the player who joined
     */
    record PlayerJoined(Player player) implements ServerMessage
    {
    }

    /**
     * Notifies all clients that a player left.
     *
     * @param playerId the ID of the player who left
     */
    record PlayerLeft(String playerId) implements ServerMessage
    {
    }

    /**
     * Server starts the game with initial state.
     *
     * @param initial the initial game state
     */
    record GameStarted(GameState initial) implements ServerMessage
    {
    }

    /**
     * Server broadcasts updated game state.
     *
     * @param state the current game state
     */
    record StateUpdate(GameState state) implements ServerMessage
    {
    }

    /**
     * Server broadcasts a chat message from a player.
     *
     * @param playerId the ID of the player who sent the message
     * @param text     the chat message text
     */
    record ChatBroadcast(String playerId, String text) implements ServerMessage
    {
    }
}
