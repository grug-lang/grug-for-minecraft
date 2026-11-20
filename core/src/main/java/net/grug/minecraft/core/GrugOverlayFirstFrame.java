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
 * records whether the mark was already set when the first frame of a screen began, and the answer
 * names the screen it is about. A read for a screen whose first frame has not been recorded is
 * false, whether that screen has not opened yet, has opened but drawn no frame, or is a different
 * screen from the one the record describes. A hook that ran before the frame left the mark clear,
 * and a hook that ran after it, or never ran, left the previous screen's mark.
 *
 * <p>A later frame of the screen that is open now confirms the record at the current screen-open
 * generation but cannot change the answer the first frame gave, so a hook that runs late cannot
 * turn its own dirty first frame into a clean one. A new screen object is what starts a new record,
 * which is what makes a hook that never runs visible: its screen still gets a first-frame record,
 * and the mark in it is the one the previous screen left.
 *
 * <p>The screen-open hook runs on the client's thread, the frame call on its render thread and the
 * answer on the thread that reads the control message, so every method is synchronized.
 */
public final class GrugOverlayFirstFrame {
    /**
     * How many times a screen has opened, so a read taken between a hook and the frame after it
     * cannot describe the frame before the hook.
     */
    private long opened;

    /** The generation of the recorded first frame, or -1 before any frame was drawn. */
    private long recorded = -1;

    /**
     * The identity of the screen the recorded frame belongs to, or 0 before any frame was drawn.
     */
    private long screenIdentity;

    /** Whether the drawn mark was already set when that first frame began. */
    private boolean marked;

    /** Notes that a screen opened, so the next frame is judged against the new generation. */
    public synchronized void opened() {
        opened++;
    }

    /**
     * Forgets the record, so the next frame of the screen that is open now starts a new first
     * frame. A read about another mod starts from here, because the mark the earlier frame saw
     * belonged to that mod's overlay.
     */
    public synchronized void forget() {
        recorded = -1;
        screenIdentity = 0;
        marked = false;
    }

    /**
     * Notes that a frame of {@code screen} is about to be drawn. The first frame of a screen
     * records the answer, and every later frame of the same screen keeps it, so a hook that runs
     * after that first frame cannot turn its dirty mark into a clean one.
     *
     * @param screen the screen this frame draws, named by identity so a new screen starts a new
     *     first frame even when the hook that should have announced it never ran
     * @param markSet whether the drawn mark was already set at the moment this frame began, before
     *     the frame drew any overlay
     */
    public synchronized void frameStarted(Object screen, boolean markSet) {
        long identity = GrugTestScreens.identityOf(screen);
        if (recorded >= 0 && identity == screenIdentity) {
            // A later frame of this screen, including the one after a re-init: it confirms the
            // record at the current generation, but the first frame already answered.
            recorded = opened;
            return;
        }
        screenIdentity = identity;
        recorded = opened;
        marked = markSet;
    }

    /**
     * Whether the drawn mark was already clear when the first frame of {@code screenIdentity}
     * began: false before a frame of that screen has begun, false when the mark was set when it
     * did, and false when the identity names no screen or a screen the record is not about. A hook
     * that ran after the first frame leaves it false, which is what makes a forwarded test's
     * assertion more than a restatement of {@code overlay_drawn}.
     *
     * @param screenIdentity the identity of the screen open now, from {@link
     *     GrugTestScreens#identityOf}, or 0 when none is open
     */
    public synchronized boolean clearedAtFirstFrame(long screenIdentity) {
        return screenIdentity != 0
                && recorded == opened
                && this.screenIdentity == screenIdentity
                && !marked;
    }
}
