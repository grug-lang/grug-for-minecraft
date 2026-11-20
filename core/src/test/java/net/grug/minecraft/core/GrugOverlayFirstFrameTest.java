package net.grug.minecraft.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The record a joined client keeps for a server-run test: whether the drawn mark was already clear
 * when a screen's first frame began (#1391).
 *
 * <p>A hook that runs before the frame leaves the mark clear, a hook that runs after it or never
 * runs leaves the previous screen's mark, and a frame that has not happened yet answers neither.
 */
class GrugOverlayFirstFrameTest {
    private final GrugOverlayFirstFrame record = new GrugOverlayFirstFrame();

    @Test
    void noFrameIsNotAnAnswer() {
        // A test that read this before the client drew its screen would otherwise take "no frame
        // yet" for a clean first frame, which is the wait overlay_drawn exists for.
        record.opened();
        assertFalse(record.clearedAtFirstFrame());
    }

    @Test
    void aHookThatRanBeforeTheFirstFrameLeavesTheMarkClear() {
        Object screen = new Object();
        record.opened();
        record.frameStarted(screen, false);
        assertTrue(record.clearedAtFirstFrame());
    }

    @Test
    void aHookThatRanAfterTheFirstFrameLeavesThePreviousScreensMark() {
        Object screen = new Object();
        record.opened();
        // The first frame of the new screen began with the mark the screen before it left.
        record.frameStarted(screen, true);
        assertFalse(record.clearedAtFirstFrame());
    }

    @Test
    void aHookThatNeverRanStillStartsANewFirstFrame() {
        Object first = new Object();
        Object second = new Object();
        // The first screen's hook ran and its frame drew, so its record is clean.
        record.opened();
        record.frameStarted(first, false);
        assertTrue(record.clearedAtFirstFrame());

        // The second screen opened with no hook, so its first frame begins with the stale mark. The
        // record must see a new screen here, or it would answer from the first screen and pass.
        record.frameStarted(second, true);
        assertFalse(record.clearedAtFirstFrame());
    }

    @Test
    void laterFramesOfTheSameScreenChangeNothing() {
        Object screen = new Object();
        record.opened();
        record.frameStarted(screen, false);
        record.frameStarted(screen, true);
        assertTrue(record.clearedAtFirstFrame(), "a later frame cannot dirty a clean first frame");

        Object dirty = new Object();
        record.opened();
        record.frameStarted(dirty, true);
        record.frameStarted(dirty, false);
        assertFalse(record.clearedAtFirstFrame(), "a later frame cannot clean a dirty first frame");
    }

    @Test
    void aHookThatRunsAgainOnTheSameScreenStartsANewFirstFrame() {
        Object screen = new Object();
        record.opened();
        record.frameStarted(screen, false);
        assertTrue(record.clearedAtFirstFrame());

        // A resize re-initializes the same screen object and its hook clears the mark; the frame
        // after that has to be judged on its own, not on the record from before the hook.
        record.opened();
        record.frameStarted(screen, false);
        assertTrue(record.clearedAtFirstFrame());
    }
}
