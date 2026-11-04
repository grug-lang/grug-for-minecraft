package net.grug.minecraft.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.grug.minecraft.core.GrugPlayerFloor.BlockSource;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

/** Covers the floor a dedicated server holds a player on in {@link GrugPlayerFloor}. */
class GrugPlayerFloorTest {

    /** A level whose blocks a test names, and which answers air everywhere else. */
    private static final class FakeLevel implements BlockSource {
        private final Set<String> solid = new HashSet<>();
        private final Set<String> nonBlocking = new HashSet<>();

        void addSolid(int x, int y, int z) {
            solid.add(x + "," + y + "," + z);
        }

        void addNonBlocking(int x, int y, int z) {
            nonBlocking.add(x + "," + y + "," + z);
        }

        @Override
        public boolean blocksMovement(int x, int y, int z) {
            String key = x + "," + y + "," + z;
            return solid.contains(key) && !nonBlocking.contains(key);
        }
    }

    /**
     * The height job 126621651945 got stuck at: the client fell into ground whose top is 71, and
     * the server accepted a position 0.051 inside the top block because the box it tested was
     * contracted. The floor is 71, the player is not on it, and the feet are inside the ground, so
     * neither readiness answer may accept the player.
     */
    @Test
    void theStuckHeightIsInsideTheGroundAndNotOnItsFloor() {
        FakeLevel level = new FakeLevel();
        level.addSolid(-24, 70, -65);

        double feet = 70.94899540560743;
        double floor = GrugPlayerFloor.floorTop(level, -23.5, feet, -64.5);

        assertEquals(71.0, floor);
        assertTrue(GrugPlayerFloor.below(feet, floor));
        assertFalse(GrugPlayerFloor.standsOn(feet, floor));
        assertTrue(GrugPlayerFloor.insideABlock(level, -23.5, feet, -64.5));
    }

    /** A player that landed on the same ground stands on the floor and is not inside a block. */
    @Test
    void aLandedPlayerStandsOnTheFloor() {
        FakeLevel level = new FakeLevel();
        level.addSolid(-24, 70, -65);

        assertEquals(71.0, GrugPlayerFloor.floorTop(level, -23.5, 71.0, -64.5));
        assertFalse(GrugPlayerFloor.below(71.0, 71.0));
        assertTrue(GrugPlayerFloor.standsOn(71.0, 71.0));
        assertFalse(GrugPlayerFloor.insideABlock(level, -23.5, 71.0, -64.5));
    }

    /**
     * The client keeps its feet a few millionths above the floor after a teleport, because its
     * position is the eye and the eye height is a float. That still counts as standing.
     */
    @Test
    void theClientsFloatErrorStillStandsOnTheFloor() {
        FakeLevel level = new FakeLevel();
        level.addSolid(-24, 70, -65);

        double feet = 71.00000000476837;
        assertEquals(71.0, GrugPlayerFloor.floorTop(level, -23.5, feet, -64.5));
        assertTrue(GrugPlayerFloor.standsOn(feet, 71.0));
        assertFalse(GrugPlayerFloor.insideABlock(level, -23.5, feet, -64.5));
    }

    /** A player still falling above the floor is not on it and not inside a block. */
    @Test
    void aFallingPlayerIsNotOnTheFloor() {
        FakeLevel level = new FakeLevel();
        level.addSolid(-24, 70, -65);

        double feet = 71.05;
        assertEquals(71.0, GrugPlayerFloor.floorTop(level, -23.5, feet, -64.5));
        assertFalse(GrugPlayerFloor.below(feet, 71.0));
        assertFalse(GrugPlayerFloor.standsOn(feet, 71.0));
        assertFalse(GrugPlayerFloor.insideABlock(level, -23.5, feet, -64.5));
    }

    /**
     * A player inside a stack is put on top of the whole stack: the floor is the top of the
     * contiguous solid column, not the top of the first solid block below the feet.
     */
    @Test
    void theFloorIsTheTopOfAContiguousSolidStack() {
        FakeLevel level = new FakeLevel();
        level.addSolid(-24, 70, -65);
        level.addSolid(-24, 71, -65);

        double feet = 70.5;
        assertEquals(72.0, GrugPlayerFloor.floorTop(level, -23.5, feet, -64.5));
        assertFalse(GrugPlayerFloor.standsOn(feet, 72.0));
        assertTrue(GrugPlayerFloor.insideABlock(level, -23.5, feet, -64.5));
    }

    /**
     * A plant at the feet does not make the player inside a block: the level's material test says
     * it does not stop movement, so a player standing in one may start a run.
     */
    @Test
    void aPlantAtTheFeetIsNotInsideABlock() {
        FakeLevel level = new FakeLevel();
        level.addSolid(-24, 70, -65);
        level.addNonBlocking(-24, 71, -65);

        assertEquals(71.0, GrugPlayerFloor.floorTop(level, -23.5, 71.0, -64.5));
        assertFalse(GrugPlayerFloor.insideABlock(level, -23.5, 71.0, -64.5));
    }

    /** Over the void there is no floor, so the player is never moved and never counts as on one. */
    @Test
    void overTheVoidThereIsNoFloor() {
        FakeLevel level = new FakeLevel();

        double feet = 40.0;
        double floor = GrugPlayerFloor.floorTop(level, -23.5, feet, -64.5);

        assertEquals(Double.NEGATIVE_INFINITY, floor);
        assertFalse(GrugPlayerFloor.standsOn(feet, floor));
        assertFalse(GrugPlayerFloor.insideABlock(level, -23.5, feet, -64.5));
    }

    /** The scan reads the block column at the feet, not a neighbouring column. */
    @Test
    void theFloorIsReadFromThePlayersOwnColumn() {
        FakeLevel level = new FakeLevel();
        level.addSolid(-24, 70, -65);

        assertEquals(Double.NEGATIVE_INFINITY, GrugPlayerFloor.floorTop(level, -22.5, 71.0, -64.5));
        assertEquals(71.0, GrugPlayerFloor.floorTop(level, -23.5, 71.0, -64.5));
    }

    /**
     * A level that answers solid everywhere cannot hang the scan: the upward walk stops at the
     * highest block it reads, so a broken level costs a bounded number of reads.
     */
    @Test
    void aColumnThatNeverEndsStopsAtTheScanTop() {
        BlockSource alwaysSolid = (x, y, z) -> true;

        assertEquals(
                GrugPlayerFloor.SCAN_TOP + 1.0,
                GrugPlayerFloor.floorTop(alwaysSolid, -23.5, 71.0, -64.5));
    }
}
