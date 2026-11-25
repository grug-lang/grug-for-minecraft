package net.grug.minecraft.ornithe;

import net.grug.minecraft.core.GrugRelight;
import net.minecraft.world.LightType;
import net.minecraft.world.World;

/**
 * Recomputes a joined client's two light fields over a box a region packet left stale (#1740).
 *
 * <p>This version lights a box by queueing an update for the field it names and draining the queue,
 * as its own per-block path does, so the settle here is the room build's on the server: both fields
 * are queued and the light ends at the one field the blocks define, whatever the packet carried.
 * See {@link GrugRelight}.
 */
public final class ClientRelight {
    /** Sweeps of a moved column's cells the light may take before it is called not settling. */
    private static final int SWEEP_LIMIT = 16;

    private ClientRelight() {}

    /**
     * Recomputes both fields of {@code box}, which is {@code {minX, minY, minZ, maxX, maxY, maxZ}}
     * in world blocks as {@link net.grug.minecraft.core.GrugChunkRelight#box} answers it.
     */
    public static void relight(World world, int[] box) {
        GrugRelight.settle(
                new QueuedLight(world),
                box[0],
                box[1],
                box[2],
                box[3],
                box[4],
                box[5],
                SWEEP_LIMIT);
    }

    /**
     * This version's queued light engine, as {@link GrugRelight} drives it: the box each call is
     * given is one the settle cut to the size the engine accepts, and the type is what decides
     * which of the two fields the update recomputes.
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
