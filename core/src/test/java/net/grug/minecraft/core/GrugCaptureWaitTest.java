package net.grug.minecraft.core;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Covers {@link GrugCaptureWait}: the latch can be driven without a game or a client, which is why
 * the timeout and the interrupt are tested here rather than by a grug test that would have to wait
 * for a frame that never comes.
 */
class GrugCaptureWaitTest {

    @Test
    void aFrameThatServedTheCaptureLeavesNoProblem() {
        CountDownLatch served = new CountDownLatch(1);
        served.countDown();

        assertNull(GrugCaptureWait.await(served));
        assertNull(GrugCaptureWait.await(served, 1_000));
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void aFrameThatNeverArrivesIsReportedRatherThanWaitedForForever() throws Exception {
        CountDownLatch never = new CountDownLatch(1);
        long started = System.nanoTime();

        String problem = GrugCaptureWait.await(never, 100);

        long waitedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertNotNull(problem, "A frame that never comes has to fail the test, not pass it.");
        assertTrue(
                problem.contains("Screenshot.capture"),
                "The failure names the call that was waiting: " + problem);
        assertTrue(
                problem.contains("the frame the capture reads"),
                "The failure names the wait: " + problem);
        assertTrue(waitedMillis < 30_000, "The wait returned after its deadline: " + waitedMillis);
    }

    @Test
    void anInterruptedWaitIsReportedAndKeepsTheInterrupt() {
        Thread.currentThread().interrupt();
        try {
            String problem = GrugCaptureWait.await(new CountDownLatch(1), 1_000);

            assertNotNull(problem, "An interrupted wait has to fail the test, not pass it.");
            assertTrue(
                    problem.contains("interrupted"),
                    "The failure says why no frame was served: " + problem);
            assertTrue(
                    Thread.currentThread().isInterrupted(),
                    "A wait that gives up on an interrupt has to keep it, for the shutdown.");
        } finally {
            Thread.interrupted();
        }
    }
}
