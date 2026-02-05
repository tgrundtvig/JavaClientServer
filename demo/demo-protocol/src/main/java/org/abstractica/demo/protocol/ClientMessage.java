package org.abstractica.demo.protocol;

/**
 * Messages sent from client to server.
 */
public sealed interface ClientMessage permits
        ClientMessage.Join,
        ClientMessage.Ready,
        ClientMessage.Move,
        ClientMessage.Chat
{
    /**
     * Player wants to join the game with the given name.
     *
     * @param playerName the display name for the player
     */
    record Join(String playerName) implements ClientMessage
    {
    }

    /**
     * Player signals they are ready to start the game.
     */
    record Ready() implements ClientMessage
    {
    }

    /**
     * Player moves to a new position.
     *
     * @param x new x position
     * @param y new y position
     */
    record Move(int x, int y) implements ClientMessage
    {
    }

    /**
     * Player sends a chat message.
     *
     * @param text the chat message text
     */
    record Chat(String text) implements ClientMessage
    {
    }
}
