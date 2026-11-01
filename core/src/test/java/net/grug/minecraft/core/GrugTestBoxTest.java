package net.grug.minecraft.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Covers {@link GrugTestBox#sweepItems}, which the runner calls in the tick a test ends. */
class GrugTestBoxTest {
    @Test
    void sweepsTheWholeTestAreaWithoutTouchingABlock() {
        List<String> calls = new ArrayList<>();
        ModLoaderAdapter adapter =
                (ModLoaderAdapter)
                        Proxy.newProxyInstance(
                                ModLoaderAdapter.class.getClassLoader(),
                                new Class<?>[] {ModLoaderAdapter.class},
                                (proxy, method, args) -> {
                                    calls.add(method.getName() + java.util.Arrays.asList(args));
                                    return null;
                                });

        GrugTestBox.sweepItems(adapter, "level", 10.5, 64.0, -20.5);

        // The area is 16 blocks around the feet block (10, 64, -21), down to one below the lowest
        // the walls reach and up to 56 above, with every upper bound one past the last block.
        assertEquals(
                List.of("clearItemEntities[level, -6.0, 47.0, -37.0, 27.0, 121.0, -4.0]"), calls);
    }

    @Test
    void theClearedAreaIsSixteenBlocksAroundTheFeetFromJustBelowTheWallsUpToFiftySixAbove() {
        // The feet block is (10, 64, -21), as in the sweep above.
        assertEquals(16, GrugTestBox.areaRadius());
        assertTrue(GrugTestBox.inClearedArea(10.5, 64.0, -20.5, 10, 64, -21));
        assertTrue(GrugTestBox.inClearedArea(10.5, 64.0, -20.5, -6, 47, -37));
        assertTrue(GrugTestBox.inClearedArea(10.5, 64.0, -20.5, 26, 120, -5));

        assertFalse(GrugTestBox.inClearedArea(10.5, 64.0, -20.5, -7, 64, -21));
        assertFalse(GrugTestBox.inClearedArea(10.5, 64.0, -20.5, 27, 64, -21));
        assertFalse(GrugTestBox.inClearedArea(10.5, 64.0, -20.5, 10, 64, -38));
        assertFalse(GrugTestBox.inClearedArea(10.5, 64.0, -20.5, 10, 64, -4));
        assertFalse(GrugTestBox.inClearedArea(10.5, 64.0, -20.5, 10, 46, -21));
        assertFalse(GrugTestBox.inClearedArea(10.5, 64.0, -20.5, 10, 121, -21));
    }

    /**
     * A world of named blocks that counts what the room build does to it. {@code canTell} is
     * whether the loader answers {@link ModLoaderAdapter#isBlock}, as 1.2.5 does and the others do
     * not.
     */
    private static final class FakeWorld {
        final Map<String, String> blocks = new HashMap<>();
        int placements = 0;
        int batchBegins = 0;
        int batchEnds = 0;
        int placementsOutsideBatch = 0;
        boolean batchOpen = false;
        final boolean canTell;
        final boolean separatesLight;
        final ModLoaderAdapter adapter;

        FakeWorld(boolean canTell) {
            this(canTell, false);
        }

        FakeWorld(boolean canTell, boolean separatesLight) {
            this.canTell = canTell;
            this.separatesLight = separatesLight;
            this.adapter =
                    (ModLoaderAdapter)
                            Proxy.newProxyInstance(
                                    ModLoaderAdapter.class.getClassLoader(),
                                    new Class<?>[] {ModLoaderAdapter.class},
                                    (proxy, method, args) -> {
                                        switch (method.getName()) {
                                            case "isAir":
                                                return !blocks.containsKey(key(args, 1));
                                            case "separatesClientLight":
                                                return FakeWorld.this.separatesLight;
                                            case "isBlock":
                                                return FakeWorld.this.canTell
                                                        && args[4].equals(blocks.get(key(args, 1)));
                                            case "beginBatch":
                                                batchBegins++;
                                                batchOpen = true;
                                                return null;
                                            case "endBatch":
                                                batchEnds++;
                                                batchOpen = false;
                                                return null;
                                            case "placeBlock":
                                                if (!batchOpen) {
                                                    placementsOutsideBatch++;
                                                }
                                                placements++;
                                                if (args[4].equals("minecraft:air")) {
                                                    blocks.remove(key(args, 1));
                                                } else {
                                                    blocks.put(key(args, 1), (String) args[4]);
                                                }
                                                return null;
                                            default:
                                                return null;
                                        }
                                    });
        }

        private static String key(Object[] args, int first) {
            return (int) Math.floor((double) args[first])
                    + " "
                    + (int) Math.floor((double) args[first + 1])
                    + " "
                    + (int) Math.floor((double) args[first + 2]);
        }

        void build(int radius) {
            placements = 0;
            GrugTestBox.build(adapter, "level", 10.5, 64.0, -20.5, radius);
        }

        void put(int x, int y, int z, String block) {
            blocks.put(x + " " + y + " " + z, block);
        }
    }

    @Test
    void aRoomThatIsWholeIsLeftInPlaceWhenTheLoaderCanTell() {
        FakeWorld world = new FakeWorld(true);
        world.build(3);
        Map<String, String> room = new HashMap<>(world.blocks);
        assertFalse(room.isEmpty());

        world.build(3);

        assertEquals(0, world.placements);
        assertEquals(room, world.blocks);
    }

    @Test
    void whatIsNotTheRoomIsClearedFromAWholeRoom() {
        FakeWorld world = new FakeWorld(true);
        world.build(3);
        Map<String, String> room = new HashMap<>(world.blocks);
        // The feet block is (10, 64, -21): one block inside the room, one far outside it but
        // inside the test area, and one at the top of the area.
        world.put(10, 65, -21, "minecraft:dirt");
        world.put(20, 70, -21, "minecraft:dirt");
        world.put(10, 120, -21, "minecraft:dirt");

        world.build(3);

        assertEquals(3, world.placements);
        assertEquals(room, world.blocks);
    }

    @Test
    void aRoomMissingABlockIsRebuiltInFull() {
        FakeWorld world = new FakeWorld(true);
        world.build(3);
        Map<String, String> room = new HashMap<>(world.blocks);
        // A torch on the floor, as an explosion would take it.
        assertEquals("minecraft:torch", world.blocks.remove("11 61 -21"));

        world.build(3);

        assertEquals(room, world.blocks);
        // Every block of the room was cleared and put back, not only the missing one.
        assertTrue(world.placements >= 2 * room.size() - 1);
    }

    @Test
    void aRoomOfTheWrongBlockIsRebuilt() {
        FakeWorld world = new FakeWorld(true);
        world.build(3);
        Map<String, String> room = new HashMap<>(world.blocks);
        world.put(10, 63, -21, "minecraft:dirt");

        world.build(3);

        assertEquals(room, world.blocks);
    }

    @Test
    void aLoaderThatCannotTellBuildsTheRoomFromScratchEveryTime() {
        FakeWorld world = new FakeWorld(false);
        world.build(3);
        Map<String, String> room = new HashMap<>(world.blocks);
        int placedFirst = world.placements;

        world.build(3);

        assertEquals(room, world.blocks);
        // The room cleared, then placed again.
        assertEquals(placedFirst + room.size(), world.placements);
    }

    @Test
    void everyBlockABuildChangesIsPlacedInsideABatch() {
        FakeWorld world = new FakeWorld(true);
        world.build(3);

        // A room from nothing: the clear, then the placements, each in its own batch.
        assertEquals(2, world.batchBegins);
        assertEquals(2, world.batchEnds);
        assertEquals(0, world.placementsOutsideBatch);

        // A whole room changes nothing of its own, so only the clear runs, in one batch.
        int begins = world.batchBegins;
        world.build(3);

        assertEquals(0, world.placements);
        assertEquals(begins + 1, world.batchBegins);
        assertEquals(world.batchBegins, world.batchEnds);
        assertEquals(0, world.placementsOutsideBatch);
    }

    @Test
    void aLoaderWhoseClientHasItsOwnLightOwesTheRebuildEverySectionOfTheRoom() {
        // A loader whose client and world are one copy cannot disagree with itself, so its rebuild
        // owes no light.
        FakeWorld shared = new FakeWorld(true);
        shared.build(3);
        assertEquals(0, GrugLightSync.pendingRoomSections());

        // A loader whose client keeps its own copy of the server's light owes every section the
        // rebuilt room's light can change, and stops owing them once the client holds them.
        FakeWorld separate = new FakeWorld(true, true);
        separate.build(3);
        assertTrue(GrugLightSync.pendingRoomSections() > 0);
        assertTrue(GrugLightSync.roomSettled((x, y, z) -> null, (x, y, z) -> null));
        assertEquals(0, GrugLightSync.pendingRoomSections());
    }

    @Test
    void theRoomIsTheSameWhetherOrNotTheLoaderCanTell() {
        FakeWorld telling = new FakeWorld(true);
        FakeWorld blind = new FakeWorld(false);
        telling.build(3);
        blind.build(3);
        assertEquals(blind.blocks, telling.blocks);

        // A bigger room after a smaller one, as Test.set_box_radius asks for.
        telling.build(5);
        blind.build(5);
        assertEquals(blind.blocks, telling.blocks);
        FakeWorld fresh = new FakeWorld(true);
        fresh.build(5);
        assertEquals(fresh.blocks, telling.blocks);
        // The walls of a bigger room reach lower than the area a smaller one clears, so a smaller
        // room after it has always left the lowest rows of those walls, which is no business of a
        // test: both ways of building leave the same world.

        // And a smaller one after a bigger one, which is the next test's default room.
        telling.build(3);
        blind.build(3);
        assertEquals(blind.blocks, telling.blocks);
    }
}
