package net.grug.minecraft.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.grug.minecraft.core.GrugWorldReady.BlockSource;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

/** Covers the world-ready probe in {@link GrugWorldReady}. */
class GrugWorldReadyTest {

    /** Terrain columns a test marks as non-air, recording every position the probe reads. */
    private static final class FakeLevel implements BlockSource {
        private final List<int[]> terrain = new ArrayList<>();
        private final List<String> reads = new ArrayList<>();

        void addColumn(int x, int z, int topY) {
            for (int y = 0; y <= topY; y++) {
                terrain.add(new int[] {x, y, z});
            }
        }

        @Override
        public boolean isAir(double x, double y, double z) {
            int blockX = (int) x;
            int blockY = (int) y;
            int blockZ = (int) z;
            reads.add(blockX + "," + blockY + "," + blockZ);
            for (int[] block : terrain) {
                if (block[0] == blockX && block[1] == blockY && block[2] == blockZ) {
                    return false;
                }
            }
            return true;
        }
    }

    @Test
    void readyWhenEveryProbedColumnHasTerrain() {
        FakeLevel level = new FakeLevel();
        level.addColumn(37, 24, 69);
        level.addColumn(48, 24, 69);
        level.addColumn(31, 24, 69);
        level.addColumn(37, 32, 69);
        level.addColumn(37, 15, 69);

        assertTrue(GrugWorldReady.isReady(level, 37.5, 70.0, 24.5));
        assertTrue(level.reads.contains("37,70,24"));
        assertTrue(level.reads.contains("48,70,24"));
        assertTrue(level.reads.contains("31,70,24"));
        assertTrue(level.reads.contains("37,70,32"));
        assertTrue(level.reads.contains("37,70,15"));
    }

    @Test
    void notReadyUntilEveryProbedColumnHasTerrain() {
        int[][] columns = {{37, 24}, {48, 24}, {31, 24}, {37, 32}, {37, 15}};
        for (int loaded = 0; loaded <= columns.length; loaded++) {
            FakeLevel level = new FakeLevel();
            for (int i = 0; i < loaded; i++) {
                level.addColumn(columns[i][0], columns[i][1], 69);
            }
            assertEquals(loaded == columns.length, GrugWorldReady.isReady(level, 37.5, 70.0, 24.5));
        }
    }

    @Test
    void probesTheNeighboursOfANegativeChunk() {
        FakeLevel level = new FakeLevel();
        level.addColumn(-19, -64, 69);
        level.addColumn(-16, -64, 69);
        level.addColumn(-33, -64, 69);
        level.addColumn(-19, -48, 69);
        level.addColumn(-19, -65, 69);

        assertTrue(GrugWorldReady.isReady(level, -18.5, 70.0, -63.5));
        assertTrue(level.reads.contains("-16,70,-64"));
        assertTrue(level.reads.contains("-33,70,-64"));
        assertTrue(level.reads.contains("-19,70,-48"));
        assertTrue(level.reads.contains("-19,70,-65"));
    }
}
