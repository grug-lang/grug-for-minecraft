package net.grug.minecraft.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.grug.minecraft.core.GrugPlayerFloor.BlockSource;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Covers the floor a dedicated server holds a player on in {@link GrugPlayerFloor}. */
class GrugPlayerFloorTest {

    /**
     * A level whose collision boxes a test names, and which answers no collision everywhere else.
     * One position may hold more than one box, as a stair does, and a box covers only its own part
     * of the block, so the answer depends on the point the floor asks about.
     */
    private static final class FakeLevel implements BlockSource {
        /** How far a point may sit outside a box's edge and still count as covered. */
        private static final double EDGE = 1e-9;

        private static final class CollisionBox {
            final double minX;
            final double minZ;
            final double maxX;
            final double maxZ;
            final double top;

            CollisionBox(double minX, double minZ, double maxX, double maxZ, double top) {
                this.minX = minX;
                this.minZ = minZ;
                this.maxX = maxX;
                this.maxZ = maxZ;
                this.top = top;
            }

            boolean covers(double localX, double localZ) {
                return localX >= minX - EDGE
                        && localX <= maxX + EDGE
                        && localZ >= minZ - EDGE
                        && localZ <= maxZ + EDGE;
            }
        }

        private final Map<String, List<CollisionBox>> boxes = new HashMap<>();

        /**
         * Positions that hold a block with no collision box, such as a plant or a torch. They are
         * not air, so a floor that gave a present block a top would answer differently for them.
         */
        private final Set<String> nonBlocking = new HashSet<>();

        /** A full cube, whose collision top is the block's own top. */
        void addSolid(int x, int y, int z) {
            addBox(x, y, z, 0.0, 0.0, 1.0, 1.0, 1.0);
        }

        /** A block that is there but has no collision box, as a plant or a torch has none. */
        void addNonBlocking(int x, int y, int z) {
            nonBlocking.add(x + "," + y + "," + z);
        }

        /** A block whose whole footprint stops the player at {@code top} above its bottom. */
        void addTop(int x, int y, int z, double top) {
            addBox(x, y, z, 0.0, 0.0, 1.0, 1.0, top);
        }

        void addBox(
                int x,
                int y,
                int z,
                double minX,
                double minZ,
                double maxX,
                double maxZ,
                double top) {
            boxes.computeIfAbsent(x + "," + y + "," + z, key -> new ArrayList<>())
                    .add(new CollisionBox(minX, minZ, maxX, maxZ, top));
        }

        @Override
        public double collisionTop(int x, int y, int z, double pointX, double pointZ) {
            String key = x + "," + y + "," + z;
            if (nonBlocking.contains(key)) {
                return BlockSource.NO_COLLISION;
            }
            List<CollisionBox> list = boxes.get(key);
            if (list == null) {
                return BlockSource.NO_COLLISION;
            }
            double localX = pointX - x;
            double localZ = pointZ - z;
            double top = BlockSource.NO_COLLISION;
            for (CollisionBox box : list) {
                if (box.covers(localX, localZ)) {
                    top = Math.max(top, box.top);
                }
            }
            return top;
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

    /**
     * The inside test and the standing test share one tolerance, so this pins the boundary: a
     * millionth above a top is not inside, a millionth under it is still standing on it (the
     * client's own arithmetic is off by about a millionth and the server accepts a fall of up to
     * 0.0625), and more than the tolerance under it is inside. A future change to {@link
     * GrugPlayerFloor#TOLERANCE} has to move all three together.
     */
    @Test
    void theInsideATopBoundaryIsTheStandingTolerance() {
        FakeLevel level = new FakeLevel();
        level.addSolid(-24, 70, -65);

        double above = 71.000001;
        assertFalse(GrugPlayerFloor.insideABlock(level, -23.5, above, -64.5));
        assertTrue(GrugPlayerFloor.standsOn(above, 71.0));

        double aMillionthUnder = 70.999999;
        assertFalse(GrugPlayerFloor.insideABlock(level, -23.5, aMillionthUnder, -64.5));
        assertTrue(GrugPlayerFloor.standsOn(aMillionthUnder, 71.0));

        double halfTheToleranceUnder = 71.0 - GrugPlayerFloor.TOLERANCE / 2.0;
        assertFalse(GrugPlayerFloor.insideABlock(level, -23.5, halfTheToleranceUnder, -64.5));
        assertTrue(GrugPlayerFloor.standsOn(halfTheToleranceUnder, 71.0));

        double twiceTheToleranceUnder = 71.0 - 2.0 * GrugPlayerFloor.TOLERANCE;
        assertTrue(GrugPlayerFloor.insideABlock(level, -23.5, twiceTheToleranceUnder, -64.5));
        assertFalse(GrugPlayerFloor.standsOn(twiceTheToleranceUnder, 71.0));
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
     * The state every dedicated-server leg lost a run to: the client joined 0.0625 inside ground
     * whose top is 71 and never reported support, so the wait on {@code onGround} timed out. The
     * server must not start there, and it must not start on the strength of {@code onGround} alone
     * either, because the room would then be built around a player inside the ground. Once the hold
     * has put the feet on the floor the run may start, whether or not the client has caught up and
     * reported support yet.
     */
    @Test
    void theStuckFeetAreNeverReadyUntilTheHoldPutsThemOnTheFloor() {
        FakeLevel level = new FakeLevel();
        level.addSolid(-24, 70, -65);

        double stuck = 70.9375;
        assertFalse(GrugPlayerFloor.mayStart(level, -23.5, stuck, -64.5, false));
        assertFalse(GrugPlayerFloor.mayStart(level, -23.5, stuck, -64.5, true));

        assertTrue(GrugPlayerFloor.mayStart(level, -23.5, 71.0, -64.5, false));
        assertTrue(GrugPlayerFloor.mayStart(level, -23.5, 71.0, -64.5, true));
    }

    /**
     * A player still falling above the floor is not on it, so a run waits for them to land, which
     * is what keeps the test origin from differing between runs (#1052). Once they report support
     * the wait ends.
     */
    @Test
    void aFallingPlayerIsOnlyReadyOnceTheyReportSupport() {
        FakeLevel level = new FakeLevel();
        level.addSolid(-24, 70, -65);

        assertFalse(GrugPlayerFloor.mayStart(level, -23.5, 71.05, -64.5, false));
        assertTrue(GrugPlayerFloor.mayStart(level, -23.5, 71.05, -64.5, true));
    }

    /** Over the void there is no floor to stand on, so only the client's own support will do. */
    @Test
    void overTheVoidOnlyTheClientsSupportIsReady() {
        FakeLevel level = new FakeLevel();

        assertFalse(GrugPlayerFloor.mayStart(level, -23.5, 40.0, -64.5, false));
        assertTrue(GrugPlayerFloor.mayStart(level, -23.5, 40.0, -64.5, true));
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
     * contiguous column of collision shapes, not the top of the first block below the feet.
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
     * A plant at the feet is a block that is there but has no collision box, so it does not make
     * the player inside a block, it is not a floor, and a player standing in one may start a run.
     * The level holds a block at 71, not air, so a floor that gave a present block a top would fail
     * here.
     */
    @Test
    void aPlantAtTheFeetIsNotInsideABlock() {
        FakeLevel level = new FakeLevel();
        level.addSolid(-24, 70, -65);
        level.addNonBlocking(-24, 71, -65);

        assertEquals(71.0, GrugPlayerFloor.floorTop(level, -23.5, 71.0, -64.5));
        assertTrue(GrugPlayerFloor.standsOn(71.0, 71.0));
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
     * A level that answers a full cube everywhere cannot hang the scan: the upward walk stops at
     * the highest block it reads, so a broken level costs a bounded number of reads.
     */
    @Test
    void aColumnThatNeverEndsStopsAtTheScanTop() {
        BlockSource alwaysSolid = (x, y, z, pointX, pointZ) -> 1.0;

        assertEquals(
                GrugPlayerFloor.SCAN_TOP + 1.0,
                GrugPlayerFloor.floorTop(alwaysSolid, -23.5, 71.0, -64.5));
    }

    /**
     * A slab at y=70 has a collision top at 70.5, so a player whose feet are there stands on it and
     * is not inside it, and the floor is its shape's top rather than the block's top at 71. Without
     * the collision shape the floor answers 71 and the hold lifts the player half a block.
     */
    @Test
    void aSlabHoldsThePlayerOnItsCollisionTop() {
        FakeLevel level = new FakeLevel();
        level.addTop(-24, 70, -65, 0.5);

        double feet = 70.5;
        assertEquals(70.5, GrugPlayerFloor.floorTop(level, -23.5, feet, -64.5));
        assertFalse(GrugPlayerFloor.below(feet, 70.5));
        assertTrue(GrugPlayerFloor.standsOn(feet, 70.5));
        assertFalse(GrugPlayerFloor.insideABlock(level, -23.5, feet, -64.5));
    }

    /** A player whose feet are inside a slab's shape is inside a block, not standing on it. */
    @Test
    void feetInsideASlabsShapeAreInsideABlock() {
        FakeLevel level = new FakeLevel();
        level.addTop(-24, 70, -65, 0.5);

        double feet = 70.2;
        assertEquals(70.5, GrugPlayerFloor.floorTop(level, -23.5, feet, -64.5));
        assertTrue(GrugPlayerFloor.below(feet, 70.5));
        assertFalse(GrugPlayerFloor.standsOn(feet, 70.5));
        assertTrue(GrugPlayerFloor.insideABlock(level, -23.5, feet, -64.5));
    }

    /**
     * A stair is two collision boxes in one block: the lower step is half a block high and the
     * upper one is a full block. A player on the lower step is held at 70.5, and one on the upper
     * step at 71, so the floor follows the box the player's own column meets.
     */
    @Test
    void aStairsStepHoldsThePlayerOnTheBoxUnderThem() {
        FakeLevel level = new FakeLevel();
        level.addBox(-24, 70, -65, 0.0, 0.0, 0.5, 1.0, 0.5);
        level.addBox(-24, 70, -65, 0.5, 0.0, 1.0, 1.0, 1.0);

        double onLowerStep = 70.5;
        assertEquals(70.5, GrugPlayerFloor.floorTop(level, -23.75, onLowerStep, -64.5));
        assertTrue(GrugPlayerFloor.standsOn(onLowerStep, 70.5));
        assertFalse(GrugPlayerFloor.insideABlock(level, -23.75, onLowerStep, -64.5));

        double onUpperStep = 71.0;
        assertEquals(71.0, GrugPlayerFloor.floorTop(level, -23.25, onUpperStep, -64.5));
        assertTrue(GrugPlayerFloor.standsOn(onUpperStep, 71.0));
        assertFalse(GrugPlayerFloor.insideABlock(level, -23.25, onUpperStep, -64.5));
    }

    /**
     * A player whose column meets the stair's taller box, with feet still at the lower step's
     * height, is inside that box: the block's own top is not what decides it.
     */
    @Test
    void feetInsideAStairsTallerBoxAreInsideABlock() {
        FakeLevel level = new FakeLevel();
        level.addBox(-24, 70, -65, 0.0, 0.0, 0.5, 1.0, 0.5);
        level.addBox(-24, 70, -65, 0.5, 0.0, 1.0, 1.0, 1.0);

        double feet = 70.5;
        assertEquals(71.0, GrugPlayerFloor.floorTop(level, -23.25, feet, -64.5));
        assertTrue(GrugPlayerFloor.below(feet, 71.0));
        assertTrue(GrugPlayerFloor.insideABlock(level, -23.25, feet, -64.5));
    }

    /**
     * Soul sand's collision shape is 0.875 of a block on b1.7.3, so a player on it stands at 70.875
     * and is not lifted to the block's top.
     */
    @Test
    void soulSandHoldsThePlayerOnItsCollisionTop() {
        FakeLevel level = new FakeLevel();
        level.addTop(-24, 70, -65, 0.875);

        double feet = 70.875;
        assertEquals(70.875, GrugPlayerFloor.floorTop(level, -23.5, feet, -64.5));
        assertTrue(GrugPlayerFloor.standsOn(feet, 70.875));
        assertFalse(GrugPlayerFloor.insideABlock(level, -23.5, feet, -64.5));
    }

    /**
     * Farmland's collision shape is the full cube on b1.7.3 even though its outline is 15/16
     * ({@code FarmlandBlock.getCollisionShape} answers {@code y} to {@code y + 1}), so a player on
     * it stands at the block top and the floor is not its lowered outline.
     */
    @Test
    void farmlandCollidesAsAFullBlock() {
        FakeLevel level = new FakeLevel();
        level.addSolid(-24, 70, -65);

        double feet = 71.0;
        assertEquals(71.0, GrugPlayerFloor.floorTop(level, -23.5, feet, -64.5));
        assertTrue(GrugPlayerFloor.standsOn(feet, 71.0));
        assertFalse(GrugPlayerFloor.insideABlock(level, -23.5, feet, -64.5));
    }
}
