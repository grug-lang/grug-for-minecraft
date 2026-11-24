package net.grug.minecraft.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

/** Covers every arm of {@link GrugArms}. */
class GrugArmsTest {
    @Test
    void askIfAsksOnlyWhenTheConditionHolds() {
        AtomicInteger asked = new AtomicInteger();
        assertEquals(
                "asked",
                GrugArms.askIf(true, "alone", () -> "asked" + asked.getAndIncrement())
                        .substring(0, 5));
        assertEquals(1, asked.get());
        assertEquals(
                "alone", GrugArms.askIf(false, "alone", () -> "asked" + asked.getAndIncrement()));
        assertEquals(1, asked.get(), "the client is not asked when nothing is owed to it");
    }

    @Test
    void runIfRunsExactlyOneArm() {
        StringBuilder ran = new StringBuilder();
        GrugArms.runIf(true, () -> ran.append("t"), () -> ran.append("o"));
        GrugArms.runIf(false, () -> ran.append("t"), () -> ran.append("o"));
        assertEquals("to", ran.toString());
    }

    @Test
    void tryAskReturnsTheAnswerOrWhatTheFailureBecomes() {
        assertEquals(7, GrugArms.tryAsk(() -> 7, e -> -1));
        int failed =
                GrugArms.tryAsk(
                        () -> {
                            throw new GrugControlServer.ControlException("no answer");
                        },
                        e -> e.getMessage().length());
        assertEquals("no answer".length(), failed);
    }

    @Test
    void mapTypeUsesThePresentArmOnlyForAnInstanceOfTheType() {
        assertEquals("s:abc", GrugArms.mapType("abc", String.class, s -> "s:" + s, () -> "none"));
        assertEquals("none", GrugArms.mapType(5, String.class, s -> "s:" + s, () -> "none"));
        assertEquals("none", GrugArms.mapType(null, String.class, s -> "s:" + s, () -> "none"));
    }

    @Test
    void ifTypeRunsExactlyOneArm() {
        StringBuilder log = new StringBuilder();
        GrugArms.ifType(
                "x",
                String.class,
                s -> log.append("present ").append(s),
                () -> log.append("absent"));
        GrugArms.ifType(
                null, String.class, s -> log.append("present"), () -> log.append(" absent"));
        assertEquals("present x absent", log.toString());
    }

    @Test
    void aPointIsInsideAFootprintOnlyWhenEveryAxisIs() {
        assertTrue(GrugArms.pointInFootprint(0.5, 0.5, 0, 1, 0, 1, 1e-9));
        assertTrue(GrugArms.pointInFootprint(1.0 + 1e-10, 0.5, 0, 1, 0, 1, 1e-9), "slack on max x");
        assertFalse(GrugArms.pointInFootprint(-0.1, 0.5, 0, 1, 0, 1, 1e-9), "left of min x");
        assertFalse(GrugArms.pointInFootprint(1.1, 0.5, 0, 1, 0, 1, 1e-9), "right of max x");
        assertFalse(GrugArms.pointInFootprint(0.5, -0.1, 0, 1, 0, 1, 1e-9), "before min z");
        assertFalse(GrugArms.pointInFootprint(0.5, 1.1, 0, 1, 0, 1, 1e-9), "after max z");
    }

    @Test
    void bothAndInRangeDecideEveryCombination() {
        assertTrue(GrugArms.both(true, true));
        assertFalse(GrugArms.both(true, false));
        assertFalse(GrugArms.both(false, true));
        assertFalse(GrugArms.both(false, false));
        assertTrue(GrugArms.inRange(1, 1, 5));
        assertTrue(GrugArms.inRange(4, 1, 5));
        assertFalse(GrugArms.inRange(0, 1, 5), "below the minimum");
        assertFalse(GrugArms.inRange(5, 1, 5), "at the limit");
    }
}
