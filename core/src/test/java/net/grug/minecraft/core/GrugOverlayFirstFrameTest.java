package net.grug.minecraft.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The record a joined client keeps for a server-run test: whether the drawn mark was already clear
 * when a screen's first frame began (#1391).
 *
 * <p>A hook that runs before the frame leaves the mark clear, a hook that runs after it or never
 * runs leaves the previous screen's mark, a frame that has not happened yet answers neither, and a
 * read that names another screen than the record describes cannot take the earlier screen's answer.
 */
class GrugOverlayFirstFrameTest {
    private final GrugOverlayFirstFrame record = new GrugOverlayFirstFrame();

    @Test
    void noFrameIsNotAnAnswer() {
        // A test that read this before the client drew its screen would otherwise take "no frame
        // yet" for a clean first frame, which is the wait overlay_drawn exists for.
        Object screen = new Object();
        long identity = GrugTestScreens.identityOf(screen);
        record.opened();
        assertFalse(record.clearedAtFirstFrame(identity));

        // A read that names no screen is false too, so a closed screen cannot borrow the record.
        assertFalse(record.clearedAtFirstFrame(0));
    }

    @Test
    void aHookThatRanBeforeTheFirstFrameLeavesTheMarkClear() {
        Object screen = new Object();
        long identity = GrugTestScreens.identityOf(screen);
        record.opened();
        record.frameStarted(screen, false);
        assertTrue(record.clearedAtFirstFrame(identity));
    }

    @Test
    void aFrameThatBeganBeforeTheHookKeepsItsSetMark() {
        Object screen = new Object();
        long identity = GrugTestScreens.identityOf(screen);
        // The first frame is drawn before the hook runs, so it begins with the mark the screen
        // before it left. The hook then clears the mark, and the next frame sees it clear.
        record.frameStarted(screen, true);
        record.opened();
        record.frameStarted(screen, false);
        // The later frame confirms the record at the new generation but cannot clean the first
        // frame, which is the answer the test asserts.
        assertFalse(record.clearedAtFirstFrame(identity));
    }

    @Test
    void aHookThatNeverRanStillStartsANewFirstFrame() {
        Object first = new Object();
        Object second = new Object();
        long firstIdentity = GrugTestScreens.identityOf(first);
        long secondIdentity = GrugTestScreens.identityOf(second);
        // The first screen's hook ran and its frame drew, so its record is clean.
        record.opened();
        record.frameStarted(first, false);
        assertTrue(record.clearedAtFirstFrame(firstIdentity));

        // The second screen opened with no hook, so its first frame begins with the stale mark. The
        // record must see a new screen here, or it would answer from the first screen and pass.
        record.frameStarted(second, true);
        assertFalse(record.clearedAtFirstFrame(secondIdentity));
        // The first screen's identity is not the one open now, so it cannot take the answer either.
        assertFalse(record.clearedAtFirstFrame(firstIdentity));
    }

    @Test
    void laterFramesOfTheSameScreenChangeNothing() {
        Object screen = new Object();
        long identity = GrugTestScreens.identityOf(screen);
        record.opened();
        record.frameStarted(screen, false);
        record.frameStarted(screen, true);
        assertTrue(
                record.clearedAtFirstFrame(identity),
                "a later frame cannot dirty a clean first frame");

        Object dirty = new Object();
        long dirtyIdentity = GrugTestScreens.identityOf(dirty);
        record.opened();
        record.frameStarted(dirty, true);
        record.frameStarted(dirty, false);
        assertFalse(
                record.clearedAtFirstFrame(dirtyIdentity),
                "a later frame cannot clean a dirty first frame");
    }

    @Test
    void aLaterHookOnTheSameScreenKeepsTheFirstFramesAnswer() {
        Object screen = new Object();
        long identity = GrugTestScreens.identityOf(screen);
        record.opened();
        record.frameStarted(screen, false);
        assertTrue(record.clearedAtFirstFrame(identity));

        // A re-init clears the mark again; its frame must not turn the first frame's clean answer
        // into a new question, and a dirty one must not turn clean either.
        record.opened();
        record.frameStarted(screen, true);
        assertTrue(record.clearedAtFirstFrame(identity));
    }

    @Test
    void forgettingTheRecordStartsTheNextFrameFresh() {
        Object screen = new Object();
        long identity = GrugTestScreens.identityOf(screen);
        record.opened();
        record.frameStarted(screen, true);
        assertFalse(record.clearedAtFirstFrame(identity));

        // A read about another mod starts from a clean record, so its own first frame answers.
        record.forget();
        assertFalse(record.clearedAtFirstFrame(identity), "no frame of the new mod has begun");
        record.frameStarted(screen, false);
        assertTrue(record.clearedAtFirstFrame(identity));
    }
}
