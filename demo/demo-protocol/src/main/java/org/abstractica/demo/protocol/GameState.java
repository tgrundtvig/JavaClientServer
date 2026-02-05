package org.abstractica.demo.protocol;

import java.util.List;

/**
 * Represents the current state of the game.
 *
 * @param players list of all players and their positions
 */
public record GameState(List<Player> players)
{
}
