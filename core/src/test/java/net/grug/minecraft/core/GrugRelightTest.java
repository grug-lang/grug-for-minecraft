package net.grug.minecraft.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

class GrugRelightTest {
    @Test
    void aSweepThatChangesNothingIsConfirmedByASecondOne() {
        AtomicInteger calls = new AtomicInteger();

        int sweeps = GrugRelight.sweepUntilStable(() -> calls.incrementAndGet() * 0 + 7, 5);

        assertEquals(2, sweeps);
        assertEquals(2, calls.get());
    }

    @Test
    void sweepsContinueWhileTheLightKeepsChanging() {
        long[] hashes = {1, 2, 3, 3};
        AtomicInteger next = new AtomicInteger();

        int sweeps = GrugRelight.sweepUntilStable(() -> hashes[next.getAndIncrement()], 5);

        assertEquals(4, sweeps);
        assertEquals(4, next.get());
    }

    @Test
    void lightThatNeverSettlesFailsInsteadOfLoopingForever() {
        AtomicInteger next = new AtomicInteger();

        IllegalStateException failure =
                assertThrows(
                        IllegalStateException.class,
                        () -> GrugRelight.sweepUntilStable(() -> next.incrementAndGet(), 4));

        assertEquals(
                "the room's light did not settle in 4 sweeps of recomputing it",
                failure.getMessage());
        assertEquals(4, next.get());
    }

    @Test
    void aLimitOfTwoAllowsExactlyOneConfirmation() {
        long[] hashes = {5, 5};
        AtomicInteger next = new AtomicInteger();

        assertEquals(2, GrugRelight.sweepUntilStable(() -> hashes[next.getAndIncrement()], 2));
    }
}
