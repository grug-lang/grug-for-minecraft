package net.grug.minecraft.ornithe;

import net.grug.minecraft.core.GrugRelight;
import net.minecraft.world.LightType;
import net.minecraft.world.World;

/**
 * The light of a test's room on this version, brought to the one field its blocks define. A
 * client's rebuild of the sections that field changed is {@link OrnitheRoomRemeshClient}.
 *
 * <p>This engine lights the world one change at a time, so the field a room ends with depends on
 * the order the changes that led to it arrived in, and on both a client and a server that order
 * differs between runs: the same room has held a wall one or two levels darker in one run than in
 * the next, which is the scatter of #1580. A cell recomputed from its neighbours is exact, so
 * recomputing every cell of the room, both fields, until a whole sweep changes nothing ends at the
 * field the blocks define whatever history the engine started from. See {@link GrugRelight}.
 *
 * <p>The sky field is swept with the block one because this engine recomputes only the field an
 * update was queued for, and the renderer bakes a face's brightness from the larger of the two: a
 * room put up where the sky was open keeps the sky's 15 inside its walls if only the block field is
 * swept, and every face of it draws at full brightness (#1368).
 *
 * <p>A client also owes the rebuild of the sections the sweep touched, which is {@link
 * OrnitheRoomRemeshClient}: it is a class of its own so that this one, which a dedicated server
 * runs too, names nothing the server cannot load.
 */
final class OrnitheRoomLight {
    /** Sweeps a room this size takes before it is not converging, which no test can work around. */
    private static final int SWEEP_LIMIT = 16;

    private OrnitheRoomLight() {}

    /** Recomputes both light fields of the box until a whole sweep changes neither. */
    static void settle(World world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        GrugRelight.settle(new QueuedLight(world), minX, minY, minZ, maxX, maxY, maxZ, SWEEP_LIMIT);
    }

    /**
     * This version's queued light engine, as {@link GrugRelight.Room} drives it: the box each call
     * is given is one {@link GrugRelight} cut to the size this engine accepts, and the type is what
     * decides which of the two fields the update recomputes.
     */
    private static final class QueuedLight implements GrugRelight.Room {
        private final World world;

        QueuedLight(World world) {
            this.world = world;
        }

        @Override
        public void queueSky(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
            this.world.updateLight(LightType.SKY, minX, minY, minZ, maxX, maxY, maxZ);
        }

        @Override
        public void queueBlock(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
            this.world.updateLight(LightType.BLOCK, minX, minY, minZ, maxX, maxY, maxZ);
        }

        @Override
        public void drain() {
            while (this.world.doLightUpdates()) {}
        }

        @Override
        public int sky(int x, int y, int z) {
            return this.world.getLight(LightType.SKY, x, y, z);
        }

        @Override
        public int block(int x, int y, int z) {
            return this.world.getLight(LightType.BLOCK, x, y, z);
        }
    }
}
