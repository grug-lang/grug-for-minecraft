package net.grug.minecraft.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** The decisions of the 1.2.5 dedicated server's glue, as pure functions. */
class GrugForge125ArmsTest {
    @Test
    void aReferenceGivesWhatItHoldsAndNothingWhenMissingOrCleared() {
        String held = "player";
        assertSame(held, GrugForge125Arms.referent(new WeakReference<>(held)));
        assertNull(GrugForge125Arms.referent((WeakReference<String>) null));
        WeakReference<String> cleared = new WeakReference<>(held);
        cleared.clear();
        assertNull(GrugForge125Arms.referent(cleared));
    }

    @Test
    void bothKnownNeedsBoth() {
        assertTrue(GrugForge125Arms.bothKnown("a", "b"));
        assertFalse(GrugForge125Arms.bothKnown(null, "b"));
        assertFalse(GrugForge125Arms.bothKnown("a", null));
        assertFalse(GrugForge125Arms.bothKnown(null, null));
    }

    @Test
    void onlyIfKeepsTheValueWhenTheConditionHolds() {
        assertEquals("v", GrugForge125Arms.onlyIf(true, "v"));
        assertNull(GrugForge125Arms.onlyIf(false, "v"));
    }

    @Test
    void bothIsAnAnd() {
        assertTrue(GrugForge125Arms.both(true, true));
        assertFalse(GrugForge125Arms.both(true, false));
        assertFalse(GrugForge125Arms.both(false, true));
        assertFalse(GrugForge125Arms.both(false, false));
    }

    @Test
    void requiredThrowsOnlyForNull() {
        assertEquals("x", GrugForge125Arms.required("x", "missing"));
        IllegalStateException thrown =
                assertThrows(
                        IllegalStateException.class,
                        () -> GrugForge125Arms.required(null, "missing"));
        assertEquals("missing", thrown.getMessage());
    }

    @Test
    void orElseGetAsksTheOtherOnlyForAnUnknownFirst() {
        AtomicInteger asked = new AtomicInteger();
        assertEquals("a", GrugForge125Arms.orElseGet("a", () -> "b" + asked.incrementAndGet()));
        assertEquals(0, asked.get());
        assertEquals("b1", GrugForge125Arms.orElseGet(null, () -> "b" + asked.incrementAndGet()));
    }

    @Test
    void theRunTicksOnlyWhileNotDoneNotAdvancingAndInCi() {
        assertTrue(GrugForge125Arms.runs(false, false, true));
        assertFalse(GrugForge125Arms.runs(true, false, true));
        assertFalse(GrugForge125Arms.runs(false, true, true));
        assertFalse(GrugForge125Arms.runs(false, false, false));
    }

    @Test
    void guardedHandsAThrowableToTheFailureAndNothingOtherwise() {
        List<Throwable> failed = new ArrayList<>();
        GrugForge125Arms.guarded(() -> {}, failed::add);
        assertTrue(failed.isEmpty());
        RuntimeException boom = new RuntimeException("boom");
        GrugForge125Arms.guarded(
                () -> {
                    throw boom;
                },
                failed::add);
        assertEquals(Collections.singletonList(boom), failed);
    }

    @Test
    void thePlayerIsLostWhenDeadWithoutHealthOrReplaced() {
        assertFalse(GrugForge125Arms.playerLost(false, 20, true));
        assertTrue(GrugForge125Arms.playerLost(true, 20, true));
        assertTrue(GrugForge125Arms.playerLost(false, 0, true));
        assertTrue(GrugForge125Arms.playerLost(false, -3, true));
        assertTrue(GrugForge125Arms.playerLost(false, 20, false));
    }

    @Test
    void healedIsTheMissingHealthAndNeverNegative() {
        assertEquals(5, GrugForge125Arms.healed(15, 20));
        assertEquals(0, GrugForge125Arms.healed(20, 20));
        assertEquals(0, GrugForge125Arms.healed(25, 20));
    }

    @Test
    void heldFeetIsZeroBeforeTheRunAndNeverAsksForAPlayer() {
        assertEquals(
                0.0,
                GrugForge125Arms.heldFeet(
                        0,
                        () -> {
                            throw new AssertionError("no player yet");
                        }));
        assertEquals(68.5, GrugForge125Arms.heldFeet(70.12, () -> 1.62), 1e-9);
    }

    @Test
    void theRunStartsOnlyForAFoundStarterWhoseWorldIsReady() {
        assertTrue(GrugForge125Arms.ready(true, true, true, () -> true));
        assertFalse(GrugForge125Arms.ready(true, true, true, () -> false));
        assertFalse(GrugForge125Arms.ready(false, true, true, () -> true));
        assertFalse(GrugForge125Arms.ready(true, false, true, () -> true));
        assertFalse(
                GrugForge125Arms.ready(
                        true,
                        true,
                        false,
                        () -> {
                            throw new AssertionError("asked about a starter that is not found");
                        }));
    }

    @Test
    void thePlayerIsGoneWhenNobodyIsHereOrTheyAreNotAmongThem() {
        assertFalse(GrugForge125Arms.playerGone(Arrays.asList("a", "b"), "b"));
        assertTrue(GrugForge125Arms.playerGone(Arrays.asList("a", "b"), "c"));
        assertTrue(GrugForge125Arms.playerGone(Collections.emptyList(), "a"));
    }

    @Test
    void theWaitBeginsAtTheFirstCallAndKeepsItsTime() {
        assertEquals(500, GrugForge125Arms.startedAt(0, 500));
        assertEquals(500, GrugForge125Arms.startedAt(500, 900));
    }

    @Test
    void aForwardedRequestAnswersOrReportsItsReason() throws Exception {
        java.util.List<String> failed = new java.util.ArrayList<>();
        assertEquals("a", GrugForge125Arms.forwarded(() -> "a", "x", failed::add, "b"));
        assertEquals(
                "b",
                GrugForge125Arms.forwarded(
                        () -> {
                            throw new GrugControlServer.ControlException("no");
                        },
                        "x",
                        failed::add,
                        "b"));
        assertEquals(Arrays.asList("x: no"), failed);
        assertNull(GrugForge125Arms.forwardedUnless(true, () -> "a", "x", failed::add));
        assertEquals("a", GrugForge125Arms.forwardedUnless(false, () -> "a", "x", failed::add));
        assertNull(
                GrugForge125Arms.forwardedUnless(
                        false,
                        () -> {
                            throw new GrugControlServer.ControlException("n");
                        },
                        "y",
                        failed::add));
        assertEquals(Arrays.asList("x: no", "y: n"), failed);
    }

    @Test
    void aSettlePollIsSettledByTheClientAndTheOwedRegion() {
        assertTrue(GrugForge125Arms.settled(false, null));
        assertTrue(GrugForge125Arms.settled(false, true));
        assertFalse(GrugForge125Arms.settled(false, false));
        assertFalse(GrugForge125Arms.settled(true, null));
        assertFalse(GrugForge125Arms.settled(true, true));
    }

    @Test
    void aSettledAnswerClearsTheOwedRegionAndOtherAnswersKeepIt() {
        GrugBlockRegion region = new GrugBlockRegion();
        region.note(1, 2, 3);
        GrugForge125Arms.heldIfSettled(null, region);
        GrugForge125Arms.heldIfSettled(false, region);
        assertFalse(region.ask((x, y, z) -> 1, (cx, cz) -> true).isEmpty());
        GrugForge125Arms.heldIfSettled(true, region);
        assertEquals("", region.ask((x, y, z) -> 1, (cx, cz) -> true));
    }

    @Test
    void aCaptureReportsABadRectangleOrAFailedAsk() {
        java.util.List<String> failed = new java.util.ArrayList<>();
        assertNull(GrugForge125Arms.captured("bad", () -> "a", failed::add));
        assertEquals("a", GrugForge125Arms.captured(null, () -> "a", failed::add));
        assertNull(
                GrugForge125Arms.captured(
                        null,
                        () -> {
                            throw new GrugControlServer.ControlException("n");
                        },
                        failed::add));
        assertEquals(Arrays.asList("Screenshot.capture: bad", "Screenshot.capture: n"), failed);
    }

    @Test
    void presentValuesAreUsedAndAbsentOnesReported() {
        java.util.List<String> failed = new java.util.ArrayList<>();
        java.util.List<String> used = new java.util.ArrayList<>();
        assertNull(GrugForge125Arms.map(null, Object::toString));
        assertEquals("1", GrugForge125Arms.map(1, Object::toString));
        GrugForge125Arms.ifPresent("v", used::add, failed::add, "m");
        GrugForge125Arms.ifPresent((String) null, used::add, failed::add, "m");
        assertEquals(Arrays.asList("v"), used);
        assertEquals(Arrays.asList("m"), failed);
    }

    @Test
    void theSecondAnswerIsAskedOnlyWhenItCanChangeTheResult() {
        assertTrue(GrugForge125Arms.allOf(true, () -> true));
        assertFalse(GrugForge125Arms.allOf(true, () -> false));
        assertFalse(
                GrugForge125Arms.allOf(
                        false,
                        () -> {
                            throw new AssertionError("asked");
                        }));
        assertTrue(
                GrugForge125Arms.answered(
                        true,
                        () -> {
                            throw new AssertionError("asked");
                        }));
        assertTrue(GrugForge125Arms.answered(false, () -> true));
        assertFalse(GrugForge125Arms.answered(false, () -> false));
    }

    @Test
    void aFallbackRunsOnlyWhenTheWorkWasNotDone() {
        int[] ran = {0};
        GrugForge125Arms.unlessDone(true, 5, n -> ran[0] += n);
        assertEquals(0, ran[0]);
        GrugForge125Arms.unlessDone(false, 5, n -> ran[0] += n);
        assertEquals(5, ran[0]);
    }

    @Test
    void theSectionMaskCoversTheSectionsABoxSpans() {
        assertEquals(0b1, GrugForge125Arms.sectionMask(0, 16));
        assertEquals(0b11, GrugForge125Arms.sectionMask(8, 16));
        assertEquals(0b1, GrugForge125Arms.sectionMask(-5, 10));
        assertEquals(0b10000000, GrugForge125Arms.sectionMask(120, 100));
        assertEquals(0, GrugForge125Arms.sectionMask(200, 4));
    }

    @Test
    void aHeldHeightIsOneThatIsNotZero() {
        assertFalse(GrugForge125Arms.holds(0));
        assertTrue(GrugForge125Arms.holds(64.5));
        assertTrue(GrugForge125Arms.holds(-3));
    }

    @Test
    void noStarterAnswersTrueWithoutAskingAndAStarterAsks() {
        assertTrue(
                GrugForge125Arms.noStarterOr(
                        false,
                        () -> {
                            throw new AssertionError("asked");
                        }));
        assertTrue(GrugForge125Arms.noStarterOr(true, () -> true));
        assertFalse(GrugForge125Arms.noStarterOr(true, () -> false));
    }
}
