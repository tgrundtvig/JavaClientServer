package org.abstractica.demo.server;

/**
 * Server-side player state attached to a session.
 */
class PlayerState
{
    private final String playerId;
    private String name;
    private int x;
    private int y;
    private boolean ready;

    PlayerState(String playerId)
    {
        this.playerId = playerId;
        this.name = null;
        this.x = 0;
        this.y = 0;
        this.ready = false;
    }

    String getPlayerId()
    {
        return playerId;
    }

    String getName()
    {
        return name;
    }

    void setName(String name)
    {
        this.name = name;
    }

    int getX()
    {
        return x;
    }

    int getY()
    {
        return y;
    }

    void setPosition(int x, int y)
    {
        this.x = x;
        this.y = y;
    }

    boolean isReady()
    {
        return ready;
    }

    void setReady(boolean ready)
    {
        this.ready = ready;
    }

    boolean hasJoined()
    {
        return name != null;
    }
}
