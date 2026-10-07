package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Covers what {@link GrugBlockGeometry} does when a block declared custom rendering but has nothing
 * to draw with.
 *
 * <p>The native side cannot be reached from a JVM test, so these cover the way of having nothing to
 * run that needs no native call: a block entity that was never created, so its handle is zero.
 * Passing that zero handle to the native side would pass a null entity pointer, which is why draw
 * checks it.
 *
 * <p>The report is read out of {@link Grug#runtimeErrorQueue} rather than through a loader adapter,
 * because that is the channel every loader drains into chat as a red message, so it is the one that
 * reaches the player. A report that only reached the log would leave a block rendering as nothing
 * without saying so. The queue is read under its own monitor, as every drain of it in the tree is.
 */
class GrugBlockGeometryTest {

    /**
     * A block description for a test to report against, distinct per test so they cannot collide.
     */
    private static final String A_BLOCK = "grug:a_machine at 1, 2, 3";

    private static final String ANOTHER_BLOCK = "grug:another_machine at 4, 5, 6";

    private static final String A_THIRD_BLOCK = "grug:a_third_machine at 7, 8, 9";

    private final List<String> reported = new ArrayList<>();

    @BeforeEach
    @AfterEach
    void drainQueue() {
        synchronized (Grug.runtimeErrorQueue) {
            Grug.runtimeErrorQueue.clear();
        }
        // draw opens and closes the pass itself, so nothing should be left open. A failing
        // assertion in the middle of one would leave every later test in this class unable to
        // open a pass.
        if (GrugRenderPass.isOpen()) {
            GrugRenderPass.close();
        }
    }

    /** Takes what the run reported, which is the message the player would have been shown. */
    private List<String> drainReports() {
        reported.clear();
        synchronized (Grug.runtimeErrorQueue) {
            reported.addAll(Grug.runtimeErrorQueue);
        }
        return reported;
    }

    @Test
    void aBlockEntityThatWasNeverCreatedIsReportedRatherThanCalled() {
        // The zero handle is what a block with no block entity script produces, and it is the half
        // of
        // the guard a JVM test can reach, since a real handle needs the native side. Passing it on
        // would hand the native code a null entity pointer, so draw has to notice it is zero.
        List<GrugBox> boxes = GrugBlockGeometry.draw(0, Grug.INVALID_GRUG_EXPORT_FN_ID, A_BLOCK);

        assertTrue(boxes.isEmpty(), "There is nothing to draw, so no boxes come back.");

        List<String> reports = drainReports();
        assertEquals(1, reports.size(), "Exactly one report, naming what the author has to fix.");
        assertTrue(
                reports.get(0).contains("set_custom_render"),
                "The report names the call that caused it: " + reports.get(0));
        assertTrue(
                reports.get(0).contains("set_block_entity"),
                "The report names the call that would fix it: " + reports.get(0));
        assertTrue(
                reports.get(0).contains(A_BLOCK),
                "The report says which block, or an author with a hundred of them is left"
                        + " searching: "
                        + reports.get(0));
    }

    @Test
    void oneBrokenBlockIsReportedOnceHoweverOftenItIsDrawn() {
        // The game redraws a block every time its chunk is rebuilt, and every loader drains this
        // queue into chat, so a report that repeated would say the same thing again on every
        // rebuild for as long as the player kept the block in view.
        GrugBlockGeometry.draw(0, Grug.INVALID_GRUG_EXPORT_FN_ID, ANOTHER_BLOCK);
        GrugBlockGeometry.draw(0, Grug.INVALID_GRUG_EXPORT_FN_ID, ANOTHER_BLOCK);
        GrugBlockGeometry.draw(0, Grug.INVALID_GRUG_EXPORT_FN_ID, ANOTHER_BLOCK);

        List<String> reports = drainReports();
        assertEquals(
                1, reports.size(), "Three draws of one broken block is one report, not three.");
        assertTrue(
                reports.get(0).contains(ANOTHER_BLOCK),
                "And it is that block's: " + reports.get(0));
    }

    @Test
    void thePassIsClosedEvenWhenThereWasNothingToRun() {
        GrugBlockGeometry.draw(0, Grug.INVALID_GRUG_EXPORT_FN_ID, A_THIRD_BLOCK);

        assertFalse(
                GrugRenderPass.isOpen(),
                "A pass left open would make the next block's pass fail its nesting invariant.");
    }

    @Test
    void twoThreadsDrawingConcurrentlyDoNotCrossContaminateOrDeadlock() throws Exception {
        // 1.20.6 rebuilds chunks on worker threads, so two block entities can be inside draw at
        // once. The state lock serializes them: each sees a clean pass, and neither deadlocks on
        // the reentrant acquisition callExportFn would make. With handle 0 no native call happens,
        // so the test exercises the lock and the pass bookkeeping without a game.
        int threadCount = 8;
        int iterations = 50;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        List<Throwable> errors = new ArrayList<>();

        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            new Thread(
                            () -> {
                                try {
                                    startLatch.await();
                                    for (int i = 0; i < iterations; i++) {
                                        GrugBlockGeometry.draw(
                                                0,
                                                Grug.INVALID_GRUG_EXPORT_FN_ID,
                                                "grug:thread_" + threadId + "_iter_" + i);
                                    }
                                } catch (Throwable e) {
                                    synchronized (errors) {
                                        errors.add(e);
                                    }
                                } finally {
                                    doneLatch.countDown();
                                }
                            })
                    .start();
        }

        startLatch.countDown();
        boolean finished = doneLatch.await(30, TimeUnit.SECONDS);

        assertTrue(finished, "Threads deadlocked: did not finish within 30 seconds");
        assertTrue(errors.isEmpty(), "Threads threw: " + errors);

        // Each thread's draws are serialized by the lock, so every pass is clean: no nesting
        // invariant was tripped, and the pass is closed at the end.
        assertFalse(
                GrugRenderPass.isOpen(),
                "A concurrent draw left the pass open, which means the lock did not serialize"
                    + " them.");
    }
}
