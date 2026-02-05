package org.abstractica.demo.protocol;

/**
 * Represents a player in the game.
 *
 * @param id   unique player identifier (session ID)
 * @param name player's display name
 * @param x    current x position on the grid
 * @param y    current y position on the grid
 */
public record Player(String id, String name, int x, int y)
{
}
