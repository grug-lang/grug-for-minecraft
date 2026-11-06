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
    void theServerHasLightLeftWhileAnyOfItsThreeQueuesDoes() {
        // Every combination, because each one is a place 1.20.6's engine can hold a rebuild's light
        // and only the first is visible through its public answer (#686).
        for (boolean engineHasLightWork : new boolean[] {false, true}) {
            for (boolean tasksQueued : new boolean[] {false, true}) {
                for (boolean updateScheduled : new boolean[] {false, true}) {
                    boolean expected = !engineHasLightWork && !tasksQueued && !updateScheduled;
                    assertEquals(
                            expected,
                            GrugRenderSettle.serverLightSettled(
                                    engineHasLightWork, tasksQueued, updateScheduled),
                            "engineHasLightWork="
                                    + engineHasLightWork
                                    + " tasksQueued="
                                    + tasksQueued
                                    + " updateScheduled="
                                    + updateScheduled);
                }
            }
        }
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

    @Test
    void theSavingIndicatorIsShownOnlyWhileItsAlphaIsDrawn() {
        // The text is drawn while floor(255 * value) is above 8, so 8/255 is the last value that is
        // not drawn and the next whole step of alpha is.
        assertFalse(GrugRenderSettle.savingIndicatorShown(true, 8.0F / 255.0F, 8.0F / 255.0F));
        assertTrue(GrugRenderSettle.savingIndicatorShown(true, 9.0F / 255.0F, 9.0F / 255.0F));
        // Nothing was saved, so there is nothing to wait for.
        assertFalse(GrugRenderSettle.savingIndicatorShown(true, 0.0F, 0.0F));
        // A save in progress walks the value toward 1.
        assertTrue(GrugRenderSettle.savingIndicatorShown(true, 1.0F, 1.0F));
        // The renderer interpolates between the two ticks' values, so the tick before the text
        // stops being drawn still draws the previous tick's stronger value.
        assertTrue(GrugRenderSettle.savingIndicatorShown(true, 0.031F, 0.04F));
        assertFalse(GrugRenderSettle.savingIndicatorShown(true, 0.031F, 0.031F));
        // The player can turn the indicator off, and then no value draws it.
        assertFalse(GrugRenderSettle.savingIndicatorShown(false, 1.0F, 1.0F));
        assertFalse(GrugRenderSettle.savingIndicatorShown(false, 0.2F, 0.0F));
    }

    @Test
    void aSaveThatEndedIsStillInFramesForTheTicksItsValueFadesOver() {
        // The measured flake (#1132): one tick into a save the value is a fifth of the way up, and
        // each client tick afterwards walks it a fifth of the way back to zero. The frames drawn
        // over those ticks still have the text, which is why the capture waits for the value to
        // reach the floor rather than for the save to end.
        int shownTicks = 0;
        float value = 0.2F;
        while (GrugRenderSettle.savingIndicatorShown(true, value, value)) {
            shownTicks++;
            value *= 0.8F;
        }
        assertEquals(8, shownTicks);
    }

    @Test
    void aCaptureIsServedOnlyByAFrameDrawnAfterItAskedAndWithoutTheIndicator() {
        // Both answers come from the loader, and a run only meets the branch where a save starts
        // inside the wait when it is unlucky. So every combination is answered here. See #1132.
        for (int combination = 0; combination < 4; combination++) {
            boolean frameDrawnAfterTheAsk = (combination & 1) != 0;
            boolean indicatorShown = (combination & 2) != 0;
            GrugRenderSettle settle = new GrugRenderSettle();
            settle.onFrame(false, false);
            long framesWhenAsked = settle.framesDrawn();
            if (frameDrawnAfterTheAsk) {
                settle.onFrame(false, false);
            }

            assertEquals(
                    frameDrawnAfterTheAsk && !indicatorShown,
                    settle.captureable(framesWhenAsked, indicatorShown),
                    "frameDrawnAfterTheAsk="
                            + frameDrawnAfterTheAsk
                            + " indicatorShown="
                            + indicatorShown);
        }
    }

    @Test
    void aCaptureAskedBeforeAnyFrameWaitsForOne() {
        GrugRenderSettle settle = new GrugRenderSettle();
        long framesWhenAsked = settle.framesDrawn();

        // The buffer can still hold a frame from before the request, which may be a previous
        // test's,
        // so the answer before a frame is drawn has to be false.
        assertFalse(settle.captureable(framesWhenAsked, false));

        settle.onFrame(false, false);

        assertTrue(settle.captureable(framesWhenAsked, false));
    }
}
