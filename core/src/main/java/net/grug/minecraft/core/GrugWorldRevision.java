package net.grug.minecraft.core;

import java.util.concurrent.atomic.AtomicLong;

/**
 * How many times a test has changed the world, which is what tells a remembered settle answer that
 * it no longer describes it (#1086).
 *
 * <p>A forwarded {@code Test.world_settled} is a round trip that freezes the world, and a wait loop
 * asks on every tick, so {@link GrugControlServer} remembers the revision the client last answered
 * settled for and answers from that until the world moves again. Every host function through which
 * a test can change the world bumps this counter, so a remembered answer cannot outlive the change
 * it was about.
 *
 * <p>A client's own run bumps it too, where nothing reads it: a static means a host function can
 * say the world moved without reaching for the loader's control channel, which a client's run does
 * not have.
 */
public final class GrugWorldRevision {
    private static final AtomicLong revision = new AtomicLong();

    private GrugWorldRevision() {}

    /** A host function changed the world, or a copy of it a client may be showing. */
    public static void bump() {
        revision.incrementAndGet();
    }

    /** The revision a settle ask carries. */
    public static long current() {
        return revision.get();
    }
}
