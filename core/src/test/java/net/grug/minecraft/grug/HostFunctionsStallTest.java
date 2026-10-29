package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class HostFunctionsStallTest {
    @Test
    void aStallThatIsNotInterruptedLeavesTheThreadAlone() {
        HostFunctions.sleepUninterrupted(1);

        assertFalse(Thread.currentThread().isInterrupted());
    }

    @Test
    void aStallOnAnInterruptedThreadReturnsAtOnceAndKeepsTheInterrupt() {
        Thread.currentThread().interrupt();
        try {
            long start = System.nanoTime();
            HostFunctions.sleepUninterrupted(10_000);

            assertTrue(System.nanoTime() - start < 5_000_000_000L);
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }
}
