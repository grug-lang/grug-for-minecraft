package net.grug.minecraft.core;

/**
 * The world revision a client last answered settled for (#1086).
 *
 * <p>Every forwarded settle ask is a round trip that freezes the world for every player, and a test
 * that waits for the client to draw its edits asks once per tick. A settled answer therefore holds
 * until the world moves again, which is what {@link GrugWorldRevision} counts: the server answers
 * from here while its revision is the one the client answered, and asks the client again only after
 * a host function has changed the world.
 *
 * <p>An unsettled answer is recorded too, so the next ask at the same revision is a fresh question
 * rather than a remembered "no": the client may have finished drawing between the two asks.
 */
final class GrugSettledRevision {
    private long revision = Long.MIN_VALUE;
    private boolean settled;

    /** Whether the client already answered settled at {@code revision}. */
    boolean answeredSettled(long revision) {
        return settled && this.revision == revision;
    }

    /** Records the client's answer for {@code revision}, which is what the next ask compares. */
    void record(long revision, boolean settled) {
        this.revision = revision;
        this.settled = settled;
    }

    /** Forgets the answer, so a run that follows this one asks the client again. */
    void forget() {
        settled = false;
    }
}
