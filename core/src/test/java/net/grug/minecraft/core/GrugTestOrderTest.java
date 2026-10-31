package net.grug.minecraft.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Covers {@link GrugTestOrder}: the order it returns and the record it reads. */
class GrugTestOrderTest {

    private static final long MS = 1_000_000L;

    private static Map<String, Long> times(long a, long b, long c) {
        return Map.of(
                "mymod/code/a-Test.grug", a * MS,
                "mymod/code/b-Test.grug", b * MS,
                "mymod/code/c-Test.grug", c * MS);
    }

    private static List<String> allThree() {
        return List.of(
                "mymod/code/a-Test.grug", "mymod/code/b-Test.grug", "mymod/code/c-Test.grug");
    }

    @Test
    void ordersTheFailedFirstThenTheRestSlowestFirst() {
        // The order the lit runner applies: what failed last goes first, and both groups run
        // slowest first, so the failure of the next run is found in its first minute.
        assertEquals(
                List.of(
                        "mymod/code/b-Test.grug",
                        "mymod/code/a-Test.grug",
                        "mymod/code/c-Test.grug"),
                GrugTestOrder.orderTests(times(10, 30, 1), Set.of(), allThree()));
        assertEquals(
                List.of(
                        "mymod/code/c-Test.grug",
                        "mymod/code/b-Test.grug",
                        "mymod/code/a-Test.grug"),
                GrugTestOrder.orderTests(
                        times(10, 30, 1), Set.of("mymod/code/c-Test.grug"), allThree()));
    }

    @Test
    void aPreviouslyFailedTestComesFirstEvenWhenItIsNotTheSlowest() {
        assertEquals(
                List.of(
                        "mymod/code/a-Test.grug",
                        "mymod/code/b-Test.grug",
                        "mymod/code/c-Test.grug"),
                GrugTestOrder.orderTests(
                        times(10, 30, 1), Set.of("mymod/code/a-Test.grug"), allThree()));
    }

    @Test
    void aFailedTestWithoutARecordedTimeStillComesFirst() {
        // The first failure of a test is what the record is for, and it has no time of its own
        // yet, so it runs last of the failed group, but still before every test that did not fail.
        Map<String, Long> times =
                Map.of(
                        "mymod/code/a-Test.grug", 10L * MS,
                        "mymod/code/b-Test.grug", 30L * MS);
        assertEquals(
                List.of(
                        "mymod/code/b-Test.grug",
                        "mymod/code/c-Test.grug",
                        "mymod/code/a-Test.grug"),
                GrugTestOrder.orderTests(
                        times,
                        Set.of("mymod/code/b-Test.grug", "mymod/code/c-Test.grug"),
                        allThree()));
    }

    @Test
    void aNewTestWithNoRecordedTimeRunsLast() {
        // It has no time, so it ties at zero with every other unrecorded test and loses the tie by
        // name against the recorded ones: the new test is the last thing a run learns about.
        assertEquals(
                List.of(
                        "mymod/code/b-Test.grug",
                        "mymod/code/a-Test.grug",
                        "mymod/code/z-Test.grug"),
                GrugTestOrder.orderTests(
                        times(10, 30, 1),
                        Set.of(),
                        List.of(
                                "mymod/code/a-Test.grug",
                                "mymod/code/z-Test.grug",
                                "mymod/code/b-Test.grug")));
    }

    @Test
    void unrecordedTestsTieAgainstEachOtherByName() {
        assertEquals(
                List.of("mymod/code/b-Test.grug", "mymod/code/z-Test.grug"),
                GrugTestOrder.orderTests(
                        Map.of(),
                        Set.of(),
                        List.of("mymod/code/z-Test.grug", "mymod/code/b-Test.grug")));
    }

    @Test
    void tiesBreakByNameSoTheOrderIsStable() {
        assertEquals(
                List.of(
                        "mymod/code/a-Test.grug",
                        "mymod/code/b-Test.grug",
                        "mymod/code/c-Test.grug"),
                GrugTestOrder.orderTests(times(5, 5, 5), Set.of(), allThree()));
    }

    @Test
    void aRemovedTestIsDroppedEvenWhenItFailed() {
        // The record is a record of the run it came from, and a test the loader no longer has is
        // not in this run, so it cannot come first.
        assertEquals(
                List.of("mymod/code/b-Test.grug", "mymod/code/a-Test.grug"),
                GrugTestOrder.orderTests(
                        times(10, 30, 1),
                        Set.of("mymod/code/c-Test.grug", "mymod/code/b-Test.grug"),
                        List.of("mymod/code/b-Test.grug", "mymod/code/a-Test.grug")));
    }

    @Test
    void anEmptyRecordKeepsNameOrder() {
        assertEquals(
                List.of(
                        "mymod/code/a-Test.grug",
                        "mymod/code/b-Test.grug",
                        "mymod/code/c-Test.grug"),
                GrugTestOrder.orderTests(Map.of(), Set.of(), allThree()));
    }

    @Test
    void aSingleTestIsItsOwnOrder() {
        assertEquals(
                List.of("mymod/code/a-Test.grug"),
                GrugTestOrder.orderTests(
                        Map.of("mymod/code/a-Test.grug", 7 * MS),
                        Set.of(),
                        List.of("mymod/code/a-Test.grug")));
    }

    @Test
    void parsesARecordWithTimesAndAFailure() {
        GrugTestOrder.Record record =
                GrugTestOrder.parseRecord(
                        "{\"failed\": [\"mymod/code/b-Test.grug\"], "
                                + "\"times\": {\"mymod/code/a-Test.grug\": 1000000, "
                                + "\"mymod/code/b-Test.grug\": 0}}");
        assertEquals(Set.of("mymod/code/b-Test.grug"), record.failed);
        assertEquals(
                Map.of("mymod/code/a-Test.grug", 1_000_000L, "mymod/code/b-Test.grug", 0L),
                record.times);
    }

    @Test
    void parsesAnEmptyRecord() {
        GrugTestOrder.Record record = GrugTestOrder.parseRecord("{\"failed\": [], \"times\": {}}");
        assertEquals(Set.of(), record.failed);
        assertEquals(Map.of(), record.times);
    }

    @Test
    void refusesTextThatIsNotJson() {
        assertThrows(IllegalArgumentException.class, () -> GrugTestOrder.parseRecord("not json"));
    }

    @Test
    void refusesTextThatIsNotAnObject() {
        for (String text : new String[] {"", "null", "[]", "5", "\"mymod\""}) {
            IllegalArgumentException e =
                    assertThrows(
                            IllegalArgumentException.class,
                            () -> GrugTestOrder.parseRecord(text),
                            text);
            assertTrue(e.getMessage().contains("JSON object"), e.getMessage());
        }
    }

    @Test
    void refusesARecordWithoutItsKeys() {
        assertThrows(
                IllegalArgumentException.class, () -> GrugTestOrder.parseRecord("{\"times\": {}}"));
        assertThrows(
                IllegalArgumentException.class,
                () -> GrugTestOrder.parseRecord("{\"failed\": []}"));
        assertThrows(IllegalArgumentException.class, () -> GrugTestOrder.parseRecord("{}"));
    }

    @Test
    void refusesARecordWhoseKeysHoldTheWrongShape() {
        assertThrows(
                IllegalArgumentException.class,
                () -> GrugTestOrder.parseRecord("{\"failed\": \"mymod\", \"times\": {}}"));
        assertThrows(
                IllegalArgumentException.class,
                () -> GrugTestOrder.parseRecord("{\"failed\": [], \"times\": []}"));
        assertThrows(
                IllegalArgumentException.class,
                () -> GrugTestOrder.parseRecord("{\"failed\": [5], \"times\": {}}"));
        assertThrows(
                IllegalArgumentException.class,
                () -> GrugTestOrder.parseRecord("{\"failed\": [{\"a\": 1}], \"times\": {}}"));
    }

    @Test
    void refusesATimeThatIsNotWholeNonNegativeNanoseconds() {
        for (String time : new String[] {"-1", "1.5", "\"ten\"", "null", "{}"}) {
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            GrugTestOrder.parseRecord(
                                    "{\"failed\": [], \"times\": {\"mymod/code/a-Test.grug\": "
                                            + time
                                            + "}}"),
                    time);
        }
    }
}
