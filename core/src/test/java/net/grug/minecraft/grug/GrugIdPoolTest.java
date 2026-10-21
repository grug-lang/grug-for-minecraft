package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Covers the search for the ids another mod has not taken. */
class GrugIdPoolTest {

    /**
     * Drains the fatal a test provoked. {@code Grug.fatal} remembers the first error per thread and
     * rethrows it at the next native call boundary, so leaving one set would fail the next test on
     * this JVM that drives an export function, for a reason unrelated to that test.
     */
    @AfterEach
    void drainPendingFatal() {
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
}
