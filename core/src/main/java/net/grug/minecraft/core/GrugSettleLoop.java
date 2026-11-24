package net.grug.minecraft.core;

/**
 * The rule that a wait which never ends reports instead of spending the test's whole tick budget
 * (#1695).
 *
 * <p>A joined client answers {@link GrugControlWire#SETTLE_NO} while it is still busy, which is
 * normal for a tick or two, and a legitimate wait can take a hundred asks while the client draws
 * the frames a room needs: one run's {@code wooden_pipe_corner_items} asked 84 times at one world
 * revision and passed on the next one. A test's wait asks once per tick for up to 2000 ticks, and
 * the server is frozen for every ask, so a client stuck in one state answers no forever while
 * nothing changes.
 *
 * <p>The rule is taken from what the client itself reports, not from one fixed count. A client
 * whose frames keep advancing is making progress however long the wait looks, so it is left to
 * {@link #LIMIT}; a client that draws no frame across {@link #STALL_LIMIT} asks at one revision is
 * not drawing slowly, it is not drawing, and it reports then. Both bounds sit above the longest
 * legitimate wait the runs have shown, and each report names the revision, how many asks it took
 * and the client's own reason, so a dispatch says which input was busy rather than only that the
 * wait did not end.
 *
 * <p>The rule is pure state so that {@code GrugSettleLoopTest} covers every case.
 */
public final class GrugSettleLoop {
    /**
     * How many unanswered asks one world revision may take before the wait reports. Above the
     * 84-ask wait a loaded run passed by a wide margin, and a fifth of the 2000-tick budget, so a
     * wait that never ends fails long before the test's own budget runs out.
     */
    static final int LIMIT = 400;

    /**
     * How many asks at one revision may pass with no frame drawn in between before the wait
     * reports, because a client that is not drawing cannot make the progress the wait is for. A
     * client drawing frames advances this count and is left to {@link #LIMIT}.
     */
    static final int STALL_LIMIT = 150;

    private boolean seen;
    private long revision;
    private String reason = "";
    private long frames = -1;
    private int asks;
    private int stalled;

    /**
     * Counts one not-settled answer at {@code revision} whose client named {@code reason} and had
     * drawn {@code frames} frames ({@code -1} when the loader cannot say), and answers the report
     * when this revision has now been asked {@link #LIMIT} times, or when {@link #STALL_LIMIT} asks
     * passed without a frame. Answers of other kinds are not passed here: a settled answer ends the
     * wait, and a divergence is {@link GrugLightRetry}'s.
     */
    public String answered(long revision, String reason, long frames) {
        String named = reason == null ? "" : reason;
        if (!seen || this.revision != revision) {
            seen = true;
            this.revision = revision;
            this.reason = named;
            this.frames = frames;
            this.asks = 1;
            this.stalled = 0;
            return null;
        }
        this.reason = named;
        this.asks++;
        if (frames < 0 || this.frames < 0) {
            this.frames = frames;
            this.stalled = 0;
        } else if (frames == this.frames) {
            this.stalled++;
        } else {
            this.frames = frames;
            this.stalled = 0;
        }
        if (asks >= LIMIT) {
            return report();
        }
        return stalled >= STALL_LIMIT ? stalledReport() : null;
    }

    /** The report for this revision: how many asked, the revision and the reason. */
    public String report() {
        return "the joined client has answered not settled "
                + asks
                + " times at world revision "
                + revision
                + ", the last time with the reason "
                + named()
                + ", so no ask can end the wait: the server is frozen while it asks, and only the"
                + " client changing could settle it";
    }

    /** The report for a client that drew no frame across the asks at one revision. */
    public String stalledReport() {
        return "the joined client has answered not settled "
                + asks
                + " times at world revision "
                + revision
                + " without drawing a frame, with the reason "
                + named()
                + ", so it is not drawing rather than drawing slowly, and no ask can end the"
                + " wait: the server is frozen while it asks, and only the client changing could"
                + " settle it";
    }

    /** The reason as a reader sees it, or that this loader does not name one. */
    public String named() {
        return reason.isEmpty() ? "which this loader does not name" : reason;
    }

    /** Forgets the run, for a new run on the same client. */
    public void forget() {
        seen = false;
        asks = 0;
        stalled = 0;
        reason = "";
        frames = -1;
    }
}
