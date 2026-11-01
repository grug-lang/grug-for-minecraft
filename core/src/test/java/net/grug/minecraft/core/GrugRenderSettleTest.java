package net.grug.minecraft.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.grug.minecraft.grug.GrugScreenshots;

import org.junit.jupiter.api.Test;

/** Covers the frame rule in {@link GrugRenderSettle}. */
class GrugRenderSettleTest {

    @Test
    void aChangeMadeAfterTheLastFrameNeedsTwoFramesAfterIt() {
        GrugRenderSettle settle = new GrugRenderSettle();
        settle.onFrame(false, false);
        settle.onFrame(false, false);
        assertTrue(settle.settled());

        settle.noteBusy();

        assertFalse(settle.settled(), "The frames drawn before the change say nothing about it.");
        settle.onFrame(false, false);
        assertFalse(settle.settled());
        settle.onFrame(false, false);
        assertTrue(settle.settled());
    }

    @Test
    void pendingWorkAtTheMomentOfTheQuestionIsNotSettledAndCountsAsBusy() {
        GrugRenderSettle settle = new GrugRenderSettle();
        settle.onFrame(false, false);
        settle.onFrame(false, false);

        assertFalse(settle.settledNow(true, true));
        assertFalse(settle.settledNow(false, true), "No frame has been drawn since the work.");
        settle.onFrame(false, false);
        settle.onFrame(false, false);
        assertTrue(settle.settledNow(false, true));
    }

    @Test
    void noFrameDrawnYetIsNotSettled() {
        GrugRenderSettle settle = new GrugRenderSettle();

        assertFalse(settle.settled());
    }

    @Test
    void oneIdleFrameIsNotEnough() {
        GrugRenderSettle settle = new GrugRenderSettle();

        settle.onFrame(false, false);

        // The capture reads the frame before the last, so a single idle frame leaves nothing
        // settled for it to read.
        assertFalse(settle.settled());

        settle.onFrame(false, false);

        assertTrue(settle.settled());
    }

    @Test
    void aBusyFrameNeedsTwoIdleFramesAfterIt() {
        GrugRenderSettle settle = new GrugRenderSettle();

        settle.onFrame(true, false);

        assertFalse(settle.settled());

        settle.onFrame(false, false);

        assertFalse(settle.settled());

        settle.onFrame(false, false);

        assertTrue(settle.settled());
    }

    @Test
    void everyBusyFrameMovesTheFrameThatIsWaitedFor() {
        GrugRenderSettle settle = new GrugRenderSettle();
        settle.onFrame(false, false);
        settle.onFrame(false, false);
        settle.onFrame(true, false);

        assertFalse(settle.settled());

        settle.onFrame(false, false);

        assertFalse(settle.settled());

        settle.onFrame(false, false);

        assertTrue(settle.settled());
    }

    @Test
    void aFrameThatStillOwedATestEditCountsAsBusyInEveryCombination() {
        // The loader passes both answers instead of combining them, so this test is what reaches
        // every outcome: no frame in a run is guaranteed to land while an edit is owed. See #804.
        for (int combination = 0; combination < 4; combination++) {
            boolean frameHadPendingWork = (combination & 1) != 0;
            boolean blocksPending = (combination & 2) != 0;
            GrugRenderSettle settle = new GrugRenderSettle();
            settle.onFrame(false, false);

            settle.onFrame(frameHadPendingWork, blocksPending);

            // Two frames are drawn, so the second one is the frame a capture would read, and it is
            // settled only when that frame had nothing pending at all.
            assertEquals(
                    !frameHadPendingWork && !blocksPending,
                    settle.settled(),
                    "frameHadPendingWork="
                            + frameHadPendingWork
                            + " blocksPending="
                            + blocksPending);
        }
    }

    @Test
    void anEditTheClientHasNotShownIsNotSettledInEveryCombination() {
        // As above: the loader passes the client's block answer in, so both outcomes of it have to
        // be reached here rather than by a poll landing at the right moment. See #804.
        for (int combination = 0; combination < 4; combination++) {
            boolean workPendingNow = (combination & 1) != 0;
            boolean blocksIdle = (combination & 2) != 0;
            GrugRenderSettle settle = new GrugRenderSettle();
            settle.onFrame(false, false);
            settle.onFrame(false, false);

            assertEquals(
                    !workPendingNow && blocksIdle,
                    settle.settledNow(workPendingNow, blocksIdle),
                    "workPendingNow=" + workPendingNow + " blocksIdle=" + blocksIdle);
        }
    }

    @Test
    void clientSettledIsAnsweredByEveryCombinationOfWhatTheLoaderReads() {
        // The whole client answer, so that no loader carries a conjunction whose branch a run only
        // meets when a poll lands at the right moment. See #424 and #804.
        for (int combination = 0; combination < 16; combination++) {
            boolean engineHasLightWork = (combination & 1) != 0;
            boolean updateQueueEmpty = (combination & 2) != 0;
            boolean framesIdle = (combination & 4) != 0;
            boolean blocksIdle = (combination & 8) != 0;

            assertEquals(
                    !engineHasLightWork && updateQueueEmpty && framesIdle && blocksIdle,
                    GrugRenderSettle.clientSettled(
                            engineHasLightWork, updateQueueEmpty, framesIdle, blocksIdle),
                    "combination=" + combination);
        }
    }

    @Test
    void aResetForgetsEveryFrameBeforeIt() {
        GrugRenderSettle settle = new GrugRenderSettle();
        settle.onFrame(false, false);
        settle.onFrame(false, false);

        assertTrue(settle.settled());

        settle.reset();

        assertFalse(settle.settled());

        settle.onFrame(false, false);

        assertFalse(settle.settled());

        settle.onFrame(false, false);

        assertTrue(settle.settled());
    }

    @Test
    void lightIsSettledOnlyWhenNeitherTheEngineNorTheUpdateQueueHoldsWork() {
        assertTrue(GrugRenderSettle.lightSettled(false, true));
        // Work in the engine's own queues.
        assertFalse(GrugRenderSettle.lightSettled(true, true));
        // Light packets received and not applied yet, which the engine cannot see (#424).
        assertFalse(GrugRenderSettle.lightSettled(false, false));
        assertFalse(GrugRenderSettle.lightSettled(true, false));
    }

    @Test
    void aFrameHasPendingWorkUntilTheWindowTheWorldAndTheChunksAreReady() {
        Object renderer = new Object();
        Object player = new Object();
        for (int width : new int[] {GrugScreenshots.WIDTH, GrugScreenshots.WIDTH - 1}) {
            for (int height : new int[] {GrugScreenshots.HEIGHT, GrugScreenshots.HEIGHT - 1}) {
                for (Object renderGlobal : new Object[] {renderer, null}) {
                    for (Object thePlayer : new Object[] {player, null}) {
                        for (boolean chunkPending : new boolean[] {false, true}) {
                            boolean expected =
                                    width != GrugScreenshots.WIDTH
                                            || height != GrugScreenshots.HEIGHT
                                            || renderGlobal == null
                                            || thePlayer == null
                                            || chunkPending;
                            assertEquals(
                                    expected,
                                    GrugRenderSettle.frameHadPendingWork(
                                            width, height, renderGlobal, thePlayer, chunkPending),
                                    "width="
                                            + width
                                            + " height="
                                            + height
                                            + " renderer="
                                            + (renderGlobal != null)
                                            + " player="
                                            + (thePlayer != null)
                                            + " chunkPending="
                                            + chunkPending);
                        }
                    }
                }
            }
        }
    }
}
