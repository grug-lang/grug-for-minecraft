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
}
