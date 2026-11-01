package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Covers the part of a block's shape that needs no game: whether a block is asked at all, and the
 * rule that turns what a call did into the boxes the game uses or the report that says why there
 * are none.
 *
 * <p>That rule is a method of its own rather than the tail of the call, so every combination of its
 * inputs is measured here: a run stops at the first {@code FAIL} line, so a grug test cannot reach
 * either report, and a Java test can. The call itself, and the boxes reaching a loader's own
 * collision, ray trace and selection code, are what {@code block_shape-Test.grug} covers on every
 * loader.
 */
class GrugShapesTest {

    private static final long UNKNOWN_FILE_ID = 987654321L;

    /**
     * One block description per test, so two tests cannot spend each other's "already reported"
     * entry and one shape's report cannot stop another test's.
     */
    private static final String A_BLOCK = "grug:a_shaped_block at 1, 2, 3";

    private static final String ANOTHER_BLOCK = "grug:another_shaped_block at 4, 5, 6";

    private static final String A_THIRD_BLOCK = "grug:a_third_shaped_block at 7, 8, 9";

    private static final String A_FOURTH_BLOCK = "grug:a_fourth_shaped_block at 10, 11, 12";

    private static final String A_FIFTH_BLOCK = "grug:a_fifth_shaped_block at 13, 14, 15";

    private static final String A_SIXTH_BLOCK = "grug:a_sixth_shaped_block at 16, 17, 18";

    private static final String A_SEVENTH_BLOCK = "grug:a_seventh_shaped_block at 19, 20, 21";

    private static final String AN_EIGHTH_BLOCK = "grug:an_eighth_shaped_block at 22, 23, 24";

    private static final GrugBox CENTRE = new GrugBox(0.25, 0.25, 0.25, 0.75, 0.75, 0.75);

    private static final GrugBox FULL_CUBE = new GrugBox(0.0, 0.0, 0.0, 1.0, 1.0, 1.0);

    /** How many threads collide on one key at a time, so a check-then-act race has to show. */
    private static final int THREADS = 8;

    /**
     * How many different keys the threads collide on. One interleaving can be lucky, so the race is
     * given many chances rather than one; the sets are never cleared, which one more key per round
     * costs nothing.
     */
    private static final int ROUNDS = 100;

    @BeforeEach
    @AfterEach
    void clearState() {
        GrugShapes.closeContext();
        GrugDeclarations.blockDataByFileId.remove(UNKNOWN_FILE_ID);
        synchronized (GrugErrors.runtimeErrorQueue) {
            GrugErrors.runtimeErrorQueue.clear();
        }
    }

    /** Takes what the run reported, which is the message the player would have been shown. */
    private static List<String> drainReports() {
        synchronized (GrugErrors.runtimeErrorQueue) {
            List<String> reports = new ArrayList<>(GrugErrors.runtimeErrorQueue);
            GrugErrors.runtimeErrorQueue.clear();
            return reports;
        }
    }

    private static GrugShapes.Call call(
            boolean ran, boolean errored, List<GrugBox> collision, List<GrugBox> selection) {
        return new GrugShapes.Call(ran, errored, collision, selection);
    }

    @Test
    void aFileIdThatIsNoBlockKeepsTheFullCube() {
        assertNull(GrugShapes.shape(UNKNOWN_FILE_ID, new Object(), 0, 0, 0));
    }

    @Test
    void aCallThatRanAndDeclaredBoxesUsesThem() {
        GrugShape shape =
                GrugShapes.decide(
                        call(
                                true,
                                false,
                                Collections.singletonList(CENTRE),
                                Collections.emptyList()),
                        A_BLOCK);

        assertEquals(Collections.singletonList(CENTRE), shape.collision());
        assertEquals(Collections.singletonList(CENTRE), shape.selection());
        assertTrue(drainReports().isEmpty(), "A shape that declared a box reports nothing.");
    }

    @Test
    void aSelectionBoxWithoutACollisionBoxIsUsedAsDeclared() {
        // A clickable block entities pass through: the collision list is empty and the selection
        // list is not, which is the one combination that reaches neither report.
        GrugShape shape =
                GrugShapes.decide(
                        call(true, false, empty(), Collections.singletonList(CENTRE)),
                        AN_EIGHTH_BLOCK);

        assertTrue(shape.collision().isEmpty());
        assertEquals(Collections.singletonList(CENTRE), shape.selection());
        assertTrue(drainReports().isEmpty());
    }

    @Test
    void aSelectionBoxOfItsOwnIsUsedAsDeclared() {
        GrugShape shape =
                GrugShapes.decide(
                        call(
                                true,
                                false,
                                Collections.singletonList(CENTRE),
                                Collections.singletonList(FULL_CUBE)),
                        ANOTHER_BLOCK);

        assertEquals(Collections.singletonList(CENTRE), shape.collision());
        assertEquals(Collections.singletonList(FULL_CUBE), shape.selection());
        assertTrue(drainReports().isEmpty());
    }

    @Test
    void aCallThatAbortedAfterAddingBoxesUsesWhatItAddedBefore() {
        // The script's own error has already been reported by the host function or the engine, so
        // the shape is not reported again and the boxes it got to declare are the shape.
        GrugShape shape =
                GrugShapes.decide(
                        call(
                                false,
                                true,
                                Collections.singletonList(CENTRE),
                                Collections.emptyList()),
                        A_THIRD_BLOCK);

        assertEquals(Collections.singletonList(CENTRE), shape.collision());
        assertTrue(drainReports().isEmpty(), "An aborted call is not reported twice.");
    }

    @Test
    void aCallThatRanAndAddedNoBoxIsReportedAndKeepsTheFullCube() {
        assertNull(GrugShapes.decide(call(true, false, empty(), empty()), A_FOURTH_BLOCK));
        // A second query for the same block must not repeat it, or chat fills up while a player
        // stands next to a broken block.
        assertNull(GrugShapes.decide(call(true, false, empty(), empty()), A_FOURTH_BLOCK));

        List<String> reports = drainReports();
        assertEquals(1, reports.size(), "A shape with no box says so once.");
        assertTrue(reports.get(0).contains(A_FOURTH_BLOCK), reports.get(0));
        assertTrue(reports.get(0).contains("added no box"), reports.get(0));
        assertTrue(
                reports.get(0).contains("add_selection_box"),
                "The report names the fix: " + reports);
    }

    @Test
    void aDeclaredShapeWithNoExportIsReportedOnceAndKeepsTheFullCube() {
        // Never added, because an export that never ran and reported no error is a script without
        // that export. A block that reported the same defect again on every collision query would
        // fill chat, so the second query is silent.
        assertNull(GrugShapes.decide(call(false, false, empty(), empty()), A_FIFTH_BLOCK));
        assertNull(GrugShapes.decide(call(false, false, empty(), empty()), A_FIFTH_BLOCK));

        List<String> reports = drainReports();
        assertEquals(1, reports.size(), "One broken block is one report, not one per query.");
        assertTrue(reports.get(0).contains(A_FIFTH_BLOCK), reports.get(0));
        assertTrue(reports.get(0).contains("exports no"), reports.get(0));
        assertTrue(reports.get(0).contains("set_shape"), reports.get(0));
    }

    @Test
    void aCallThatAbortedBeforeAddingAnyBoxKeepsTheFullCubeSilently() {
        // The error itself has been reported, and there is no second, different defect to name.
        assertNull(GrugShapes.decide(call(false, true, empty(), empty()), A_SIXTH_BLOCK));

        assertTrue(drainReports().isEmpty());
    }

    @Test
    void aCallWithNoEntityKeepsTheFullCubeSilently() {
        // The block's script made no entity to run the call on, which is not a shape defect its
        // author can be told about and not a shape either, so the game keeps the cube.
        assertNull(GrugShapes.decide(null, A_SEVENTH_BLOCK));

        assertTrue(drainReports().isEmpty());
    }

    @Test
    void anUndeclaredExportIsReportedOnceAndKeepsTheFullCube() {
        GrugShapes.reportUndeclaredShape(call(true, false, empty(), empty()), A_BLOCK);
        GrugShapes.reportUndeclaredShape(call(true, false, empty(), empty()), A_BLOCK);

        List<String> reports = drainReports();
        assertEquals(
                1, reports.size(), "One block that forgot to declare its shape is one report.");
        assertTrue(reports.get(0).contains(A_BLOCK), reports.get(0));
        assertTrue(reports.get(0).contains("never called set_shape"), reports.get(0));
        assertTrue(reports.get(0).contains("remove the export"), reports.get(0));
    }

    @Test
    void anUndeclaredExportThatNeverRanIsNotReported() {
        // A block with no shape export at all is the ordinary case, and its one probe must be
        // silent. A block with no entity cannot be asked, so it is not reported either.
        GrugShapes.reportUndeclaredShape(call(false, false, empty(), empty()), ANOTHER_BLOCK);
        GrugShapes.reportUndeclaredShape(null, A_THIRD_BLOCK);

        assertTrue(drainReports().isEmpty());
    }

    @Test
    void anOpenContextRecordsBothKindsOfBox() {
        GrugShapes.Context context = GrugShapes.openContext();

        assertTrue(GrugShapes.recordCollision(CENTRE));
        assertTrue(GrugShapes.recordSelection(FULL_CUBE));

        assertEquals(Collections.singletonList(CENTRE), context.collision());
        assertEquals(Collections.singletonList(FULL_CUBE), context.selection());
    }

    @Test
    void aSecondContextKeepsTheBoxesTheFirstOneAlreadyDeclared() {
        // A call that nested inside another one must not wipe the outer call's boxes. It replaces
        // the one open context, which is the whole reason only one call runs at a time, but the
        // boxes recorded before it are the outer call's own and stay with it.
        GrugShapes.Context outer = GrugShapes.openContext();
        assertTrue(GrugShapes.recordCollision(CENTRE));

        GrugShapes.Context inner = GrugShapes.openContext();
        assertTrue(GrugShapes.recordCollision(FULL_CUBE));

        assertEquals(Collections.singletonList(CENTRE), outer.collision());
        assertEquals(Collections.singletonList(FULL_CUBE), inner.collision());
        assertSame(inner, GrugShapes.context());
    }

    @Test
    void aBoxWithItsCornersTheWrongWayRoundIsNotRecorded() {
        GrugShapes.openContext();

        assertFalse(GrugShapes.recordCollision(new GrugBox(1, 0, 0, 0, 1, 1)));
        assertFalse(GrugShapes.recordSelection(new GrugBox(0, 1, 1, 1, 0, 0)));
    }

    @Test
    void aBoxOutsideACallIsNotRecorded() {
        assertFalse(GrugShapes.recordCollision(FULL_CUBE));
        assertFalse(GrugShapes.recordSelection(FULL_CUBE));
    }

    /**
     * The client thread and the integrated server thread both ask a block for its shape, so both
     * spend its one probe. A set that is not concurrent lets both of them win the same block, which
     * enters the VM twice for a block that exports {@code shape()} without declaring it and can
     * name the mismatch twice.
     */
    @Test
    void aBlockIsProbedOnceWhenBothGameThreadsAskAtOnce() throws Exception {
        ExecutorService threads = Executors.newFixedThreadPool(THREADS);
        try {
            for (int round = 0; round < ROUNDS; round++) {
                // Negative, so a probe spent here cannot be a block a test or a compile handed out.
                long blockFileId = -(round + 1);
                int claimants = 0;
                for (boolean claimed :
                        runAtOnce(threads, () -> GrugShapes.probeOnce(blockFileId))) {
                    if (claimed) {
                        claimants++;
                    }
                }
                assertEquals(
                        1,
                        claimants,
                        "A block's one probe was spent by "
                                + claimants
                                + " threads at once, round "
                                + round);
            }
        } finally {
            threads.shutdownNow();
        }
    }

    /**
     * Both game threads reach a broken block's shape query too, and they reach it after the shape
     * call that held the state lock has returned. A set that is not concurrent reports the same
     * block more than once, which is the chat full of identical lines the set exists to prevent.
     */
    @Test
    void aDefectIsReportedOnceWhenBothGameThreadsFindItAtOnce() throws Exception {
        ExecutorService threads = Executors.newFixedThreadPool(THREADS);
        try {
            for (int round = 0; round < ROUNDS; round++) {
                String description = "grug:raced_block_" + round + " at 0, 0, 0";
                runAtOnce(
                        threads,
                        () -> {
                            GrugShapes.decide(call(true, false, empty(), empty()), description);
                            return null;
                        });
            }
        } finally {
            threads.shutdownNow();
        }

        List<String> reports = drainReports();
        assertEquals(ROUNDS, reports.size(), "One broken block is one report, not one per query.");
        for (int round = 0; round < ROUNDS; round++) {
            String marker = "raced_block_" + round + " ";
            int matching = 0;
            for (String report : reports) {
                if (report.contains(marker)) {
                    matching++;
                }
            }
            assertEquals(
                    1,
                    matching,
                    "The block at " + marker.trim() + " was reported " + matching + " times");
        }
    }

    /**
     * Runs {@code task} on every thread at once, all released by one barrier so they collide rather
     * than start one after another, and hands back what each answered.
     */
    private static <T> List<T> runAtOnce(ExecutorService threads, Callable<T> task)
            throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> answers = new ArrayList<>();
        for (int thread = 0; thread < THREADS; thread++) {
            answers.add(
                    threads.submit(
                            () -> {
                                start.await();
                                return task.call();
                            }));
        }
        start.countDown();
        List<T> results = new ArrayList<>();
        for (Future<T> answer : answers) {
            results.add(answer.get());
        }
        return results;
    }

    private static List<GrugBox> empty() {
        return Collections.emptyList();
    }
}
