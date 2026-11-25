package net.grug.minecraft.core;

/**
 * The world revision a client last answered settled for, and the one whose queued block changes
 * were last flushed to it (#1086).
 *
 * <p>Every forwarded settle ask is a round trip that freezes the world for every player, and a test
 * that waits for the client to draw its edits asks once per tick. A settled answer therefore holds
 * until the world moves again, which is what {@link GrugWorldRevision} counts: the server answers
 * from here while its revision is the one the client answered, and asks the client again only after
 * a host function has changed the world.
 *
 * <p>The question itself is part of the key. A settle ask also carries what the client has to hold
 * besides the frames, which a dedicated server fills with the light box it last sent. A box can
 * change without the revision moving, because a mod's own game-tick callback places a block rather
 * than a test's export, and a settled answer for the old box says nothing about the new one: the
 * client was never asked about it, so it is asked now (#1695).
 *
 * <p>The block changes are flushed once per revision too. A repeat ask at a revision whose changes
 * were already sent must not broadcast them again: a wait of N ticks would otherwise send every
 * queued change N times, and an early send is what a later test's client saw (#1078).
 *
 * <p>An unsettled answer is recorded too, so the next ask at the same revision is a fresh question
 * rather than a remembered "no": the client may have finished drawing between the two asks.
 */
final class GrugSettledRevision {
    private long revision = Long.MIN_VALUE;
    private boolean settled;
    private String held = "";
    private long flushedRevision = Long.MIN_VALUE;

    /** Whether the client already answered settled at {@code revision} for this same question. */
    boolean answeredSettled(long revision, String held) {
        return settled && this.revision == revision && this.held.equals(text(held));
    }

    /** Whether {@code revision}'s queued block changes have not been flushed yet. */
    boolean needsFlush(long revision) {
        return revision != flushedRevision;
    }

    /** Records that {@code revision}'s queued block changes were flushed. */
    void flushed(long revision) {
        flushedRevision = revision;
    }

    /** Records the client's answer for {@code revision}, which is what the next ask compares. */
    void record(long revision, boolean settled, String held) {
        this.revision = revision;
        this.settled = settled;
        this.held = text(held);
    }

    /** Forgets the answer and the flush, so a run that follows this one asks and sends again. */
    void forget() {
        settled = false;
        held = "";
        flushedRevision = Long.MIN_VALUE;
    }

    private static String text(String held) {
        return held == null ? "" : held;
    }
}
