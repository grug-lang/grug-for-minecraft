package net.grug.minecraft.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Covers {@link GrugTestPlayerMove#canMove}: a move needs a player, a world and the target chunk,
 * and every combination of the three decides the same way on every loader.
 */
class GrugTestPlayerMoveTest {
    @Test
    void aMoveIsAllowedOnlyWhenThePlayerTheWorldAndTheChunkAreAllThere() {
        assertTrue(GrugTestPlayerMove.canMove(true, true, true));
        assertFalse(GrugTestPlayerMove.canMove(false, true, true), "no player to move");
        assertFalse(GrugTestPlayerMove.canMove(true, false, true), "no world to move them in");
        assertFalse(GrugTestPlayerMove.canMove(true, true, false), "the chunk has not arrived");
        assertFalse(GrugTestPlayerMove.canMove(false, false, true));
        assertFalse(GrugTestPlayerMove.canMove(false, true, false));
        assertFalse(GrugTestPlayerMove.canMove(true, false, false));
        assertFalse(GrugTestPlayerMove.canMove(false, false, false));
    }

    @Test
    void aMultiplayerClientsPlaceholderChunkIsNotReceived() {
        assertTrue(GrugTestPlayerMove.chunkReceived(true, false));
        assertFalse(
                GrugTestPlayerMove.chunkReceived(true, true), "isChunkLoaded lies on multiplayer");
        assertFalse(GrugTestPlayerMove.chunkReceived(false, false));
        assertFalse(GrugTestPlayerMove.chunkReceived(false, true));
    }

    @Test
    void canMoveToNeedsAPlayerAWorldAndTheChunkOfThatWorld() {
        assertTrue(GrugTestPlayerMove.canMoveTo("p", "w", w -> true));
        assertFalse(GrugTestPlayerMove.canMoveTo("p", "w", w -> false));
        assertFalse(GrugTestPlayerMove.canMoveTo("p", null, w -> true));
        assertFalse(GrugTestPlayerMove.canMoveTo(null, "w", w -> true));
    }
}
