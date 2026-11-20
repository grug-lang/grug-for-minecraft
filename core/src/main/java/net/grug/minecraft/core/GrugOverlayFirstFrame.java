package net.grug.minecraft.core;

/**
 * Records whether the overlay's drawn mark was already set when the first frame of a screen began,
 * which is how a server-run test proves the client's screen-open hook ran before that frame instead
 * of after it (#1391).
 *
 * <p>The screen-open hook clears the drawn mark, and the first frame that draws the screen's
 * overlay sets it again. A client that runs a test itself reads the mark right after it opens the
 * screen, before any frame has been drawn, so an unset mark there proves the hook ran. A joined
 * client cannot promise that: it draws frames of its own between the server's open and the server's
 * read, so by the time the server asks, the mark is set whether or not the hook ran before the
 * first frame.
 *
 * <p>The comparison is therefore kept on the client, at the moment a frame is drawn: this class
 * notes whether the mark was already set when the first frame of the screen open now began. A hook
 * that ran before that frame left the mark clear there, and a hook that ran after it, or never ran,
 * left the previous screen's mark set. {@link #clearedAtFirstFrame} is what a forwarded test reads,
 * and it is false until a frame has begun, so a caller cannot mistake "no frame yet" for the
 * property the test wants.
 *
 * <p>A new screen is a screen a frame has not been recorded for yet, either because the object
 * differs or because a screen-open hook ran since. That is what makes a hook that never runs
 * visible: its screen still gets a first frame record, and the mark in it is the one the previous
 * screen left, so the answer is false. A rule that keyed only on the hook would take a hook that
 * never ran for "the same screen as before" and answer from the earlier screen's record.
 *
 * <p>The screen-open hook runs on the client's thread, the frame call on its render thread and the
 * answer on the thread that reads the control message, so every method is synchronized.
 */
public final class GrugOverlayFirstFrame {
    /**
     * How many times a screen has opened or closed, so a screen whose object is drawn again after a
     * hook still starts a new first frame.
     */
    private long opened;

    /** The generation of the recorded first frame, or -1 before any frame was drawn. */
    private long recorded = -1;

    /** The screen the recorded frame belongs to, or null before any frame was drawn. */
    private Object screen;

    /** Whether the drawn mark was already set when that first frame began. */
    private boolean marked;

    /** Notes that a screen opened or closed, so the next frame drawn is a new first frame. */
    public synchronized void opened() {
        opened++;
    }

    /**
     * Notes that a frame of {@code screen} is about to be drawn. The first such frame of a screen
     * records the answer and the frames after it change nothing, so a later frame cannot turn a
     * stale first frame into a clean one.
     *
     * @param screen the screen this frame draws, compared by identity so a new screen starts a new
     *     first frame even when the hook that should have announced it never ran
     * @param markSet whether the drawn mark was already set at the moment this frame began, before
     *     the frame drew any overlay
     */
    public synchronized void frameStarted(Object screen, boolean markSet) {
        if (recorded == opened && screen == this.screen) return;
        recorded = opened;
        this.screen = screen;
        marked = markSet;
    }

    /**
     * Whether the drawn mark was already clear when the first frame of the screen open now began:
     * false before that frame has begun, and false when the mark was set when it did. A hook that
     * ran after the first frame leaves it false, which is what makes a forwarded test's assertion
     * more than a restatement of {@code overlay_drawn}.
     */
    public synchronized boolean clearedAtFirstFrame() {
        return recorded == opened && !marked;
    }
}
