package net.grug.minecraft.core;

/**
 * The rule that a wait which never ends reports instead of spending the test's whole tick budget
 * (#1695).
 *
 * <p>A joined client answers {@link GrugControlWire#SETTLE_NO} while it is still busy, which is
 * normal for a tick or two. A test's wait asks once per tick for up to 2000 ticks, and the server
 * is frozen for every ask, so a client stuck in one state answers no forever while nothing changes:
 * no tick passes on the server, so nothing the wait can send or draw resolves it. Two runs have
 * spent five minutes of a leg that way, one at a revision the client had already answered and never
 * left (job 138751741250 on {@code a1.1.2_01-ornithe}).
 *
 * <p>A run of answers therefore counts while the revision and the reason stay the same, and reports
 * once it reaches {@link #LIMIT}. A revision the world moved past, or a reason that changed between
 * asks, starts the count again: the wait is making progress or asking about a new world, and the
 * ordinary case costs no extra ask. The rule is pure state so that {@code GrugSettleLoopTest}
 * covers each case.
 */
public final class GrugSettleLoop {
    /**
     * How many unanswered asks at one revision with one reason a wait may make before it reports.
     * Well under the 2000-tick budget, so a stuck wait fails at about a tenth of it rather than at
     * its end.
     */
    static final int LIMIT = 100;

    private boolean seen;
    private long revision;
    private String reason = "";
    private int count;

    /**
     * Counts one not-settled answer at {@code revision} whose client named {@code reason}, and
     * answers the report when that pair has now been given {@link #LIMIT} times in a row (answers
     * of other kinds are not passed here: a settled answer ends the wait, and a divergence is
     * {@link GrugLightRetry}'s).
     */
    public String answered(long revision, String reason) {
        String named = reason == null ? "" : reason;
        if (!seen || this.revision != revision || !this.reason.equals(named)) {
            seen = true;
            this.revision = revision;
            this.reason = named;
            count = 1;
            return null;
        }
        count++;
        return count >= LIMIT ? report() : null;
    }

    /** The report for this run of answers: the revision, the reason and how many asked. */
    public String report() {
        return "the joined client has answered not settled "
                + count
                + " times at world revision "
                + revision
                + " with the same reason, "
                + named()
                + ", so no ask can end the wait: the server is frozen while it asks, and only the"
                + " client changing could settle it";
    }

    /** The reason as a reader sees it, or that this loader does not name one. */
    public String named() {
        return reason.isEmpty() ? "which this loader does not name" : reason;
    }

    /** Forgets the run, for a new run on the same client. */
    public void forget() {
        seen = false;
        count = 0;
        reason = "";
    }
}
