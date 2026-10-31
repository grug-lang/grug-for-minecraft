package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Covers the search for the ids another mod has not taken. */
class GrugIdPoolTest {

    /**
     * Forgets the shortage a test recorded, and drains the fatal one provoked. {@code Grug.fatal}
     * remembers the first error per thread and rethrows it at the next native call boundary, so
     * leaving one set would fail the next test on this JVM that drives an export function, for a
     * reason unrelated to that test.
     */
    @AfterEach
    void drainPendingFatal() {
        GrugIdShortage.clear();
        try {
            GrugErrors.throwPendingFatal();
        } catch (IllegalStateException expected) {
            // The fatal the test provoked, cleared so it cannot outlive the test.
        }
    }

    private static Set<Integer> taken(Integer... ids) {
        return new HashSet<>(Arrays.asList(ids));
    }

    @Test
    void anEmptyRangeHoldsEveryId() {
        assertArrayEquals(new int[] {5, 6, 7}, GrugIdPool.freeIds(id -> false, 5, 7));
    }

    @Test
    void takenIdsAreSkipped() {
        Set<Integer> taken = taken(5, 7, 8);
        assertArrayEquals(new int[] {6, 9}, GrugIdPool.freeIds(taken::contains, 5, 9));
    }

    @Test
    void theEndsOfTheRangeAreBothIncluded() {
        assertArrayEquals(new int[] {5, 9}, GrugIdPool.freeIds(id -> id > 5 && id < 9, 5, 9));
    }

    @Test
    void aFullRangeHoldsNoId() {
        assertEquals(0, GrugIdPool.freeIds(id -> true, 5, 9).length);
    }

    @Test
    void aRangeThatEndsBeforeItStartsHoldsNoId() {
        assertEquals(0, GrugIdPool.freeIds(id -> false, 9, 5).length);
    }

    @Test
    void rangesAreSearchedInOrderAndTheGapBetweenThemIsLeftFree() {
        // The 1.2.5 shape: 125 to 254, then 256 to 4095, with 255 outside both ranges because
        // some mods hardcode it.
        int[][] ranges = {{125, 254}, {256, 4095}};
        int[] free = GrugIdPool.freeIds(id -> false, ranges);

        assertEquals(125, free[0]);
        assertEquals(254, free[129]);
        assertEquals(256, free[130]);
        assertEquals(4095, free[free.length - 1]);
        assertEquals(3970, free.length);
    }

    @Test
    void aFilledFirstRangeHandsOutTheSecond() {
        // 1.2.5 with the pack's other mods over the low range: the search spills past the gap.
        int[][] ranges = {{125, 254}, {256, 4095}};
        int[] free = GrugIdPool.freeIds(id -> id <= 254, ranges);

        assertEquals(256, free[0]);
        assertEquals(4095, free[free.length - 1]);
    }

    @Test
    void anIdIsFreeOnlyWhenEveryTableSaysSo() {
        Set<Integer> oneTable = taken(256, 257, 258);
        Set<Integer> otherTable = taken(258, 259);
        // Both tables occupied: an id held by either is taken.
        int[] free =
                GrugIdPool.freeIds(
                        id -> oneTable.contains(id) || otherTable.contains(id),
                        new int[][] {{256, 260}});

        assertArrayEquals(new int[] {260}, free);
    }

    @Test
    void aPoolOverSeveralRangesTakesLowestFirstAcrossTheGap() {
        GrugIdPool pool =
                GrugIdPool.of("block", id -> id <= 254, new int[][] {{125, 254}, {256, 4095}});

        assertEquals(3840, pool.remaining());
        assertEquals(256, pool.take());
        assertEquals(257, pool.take());
        assertEquals(3838, pool.remaining());
    }

    @Test
    void idsAreHandedOutLowestFirstAndCounted() {
        Set<Integer> taken = taken(6);
        GrugIdPool pool = GrugIdPool.of("block", taken::contains, 5, 8);

        assertEquals(3, pool.remaining());
        assertEquals(5, pool.take());
        assertEquals(7, pool.take());
        assertEquals(1, pool.remaining());
        assertEquals(8, pool.take());
        assertEquals(0, pool.remaining());
    }

    @Test
    void takingFromAnEmptyPoolIsFatalAndNamesTheKind() {
        GrugIdPool pool = GrugIdPool.of("item", id -> true, 400, 410);

        IllegalStateException error = assertThrows(IllegalStateException.class, pool::take);

        assertTrue(
                error.getMessage().contains("No free item id for a grug item"), error.getMessage());
    }

    private static Map<String, String> mods(String mod, int count, Map<String, String> into) {
        for (int i = 0; i < count; i++) {
            into.put(mod + "_" + i, mod);
        }
        return into;
    }

    private static Map<String, String> needs() {
        Map<String, String> needs = new LinkedHashMap<>();
        mods("buildcraft", 3, needs);
        mods("coverage", 32, needs);
        mods("examplemod", 1, needs);
        return needs;
    }

    @Test
    void modsThatFitLeaveNoShortage() {
        Set<Integer> taken = taken(130, 131);
        GrugIdPool pool = GrugIdPool.of("block", taken::contains, 125, 170);

        assertTrue(pool.hasRoomFor(needs()));
        assertTrue(GrugIdShortage.all().isEmpty());
    }

    @Test
    void modsThatDoNotFitRecordHowShortTheIdsAreAndWhatEachModNeeds() {
        Set<Integer> taken = taken(130, 131);
        GrugIdPool pool = GrugIdPool.of("block", taken::contains, 125, 160);
        pool.take();

        assertFalse(pool.hasRoomFor(needs()));

        assertEquals(1, GrugIdShortage.all().size());
        GrugIdShortage shortage = GrugIdShortage.all().get(0);
        assertEquals("block", shortage.kind());
        assertEquals(36, shortage.needed());
        assertEquals(33, shortage.free());
        assertEquals(3, shortage.missing());
        assertEquals(32, shortage.needs("coverage"));
        assertEquals(3, shortage.needs("buildcraft"));
        assertEquals(0, shortage.needs("unknown"));
    }

    @Test
    void aNameTwoModsShipTakesOneIdAndIsCountedOnce() {
        GrugIdPool pool = GrugIdPool.of("block", id -> false, 125, 126);
        Map<String, String> needs = new LinkedHashMap<>();
        // Registration keys by name, so the second put replaced the first mod's block.
        needs.put("foo", "first");
        needs.put("foo", "second");
        needs.put("bar", "second");

        assertTrue(pool.hasRoomFor(needs));
    }

    @Test
    void failIfShortNamesEveryKindAndEveryModMostFirst() {
        GrugIdShortage.failIfShort(); // nothing is short yet

        Map<String, String> items = new LinkedHashMap<>();
        mods("zeta", 2, items);
        mods("alpha", 2, items);
        assertFalse(GrugIdPool.of("item", id -> true, 400, 410).hasRoomFor(items));
        assertFalse(GrugIdPool.of("block", taken(130)::contains, 125, 160).hasRoomFor(needs()));

        IllegalStateException error =
                assertThrows(IllegalStateException.class, GrugIdShortage::failIfShort);

        String message = error.getMessage();
        assertTrue(
                message.contains("Not enough free item ids for grug's items: they need 4 and 0"),
                message);
        assertTrue(message.contains("\n  alpha: 2\n  zeta: 2"), message);
        assertTrue(message.contains("\nNot enough free block ids"), message);
        assertTrue(message.contains("\n  coverage: 32\n  buildcraft: 3\n  examplemod: 1"), message);
    }
}
