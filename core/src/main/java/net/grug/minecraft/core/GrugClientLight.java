package net.grug.minecraft.core;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A test's read of the joined client's own copy of a section's block light (#1207, #1240).
 *
 * <p>A test runs on the side that simulates the world, and on a dedicated server that side is the
 * server, whose light is not the one the joined client's frame pass bakes the room from. A capture
 * can show that the frame changed, but not whether the light reached the client or something else
 * moved the frame; two frames of a room drawn from the light it had before a change agree with each
 * other. This read answers the one fact a light-verifying test cannot infer.
 *
 * <p>The client's half is {@link ModLoaderAdapter#clientLightFingerprint}, which both the call on a
 * client and the forwarded answer of {@link GrugControlClient} go through, so a singleplayer read
 * and a forwarded one cannot differ. {@link Forwarded} is the other end, the read a dedicated
 * server makes of the client of the player who started the run.
 */
public final class GrugClientLight {

    private GrugClientLight() {}

    /**
     * The reads a dedicated server makes, which the joined client of the player who started the run
     * answers: a server holds the simulated copy, not the one that client's frame pass reads.
     *
     * <p>The server comes in as a supplier rather than as a server, because a loader creates it on
     * the first use and the adapter that holds this object outlives that, as with the saved-number
     * and screen calls.
     */
    public static final class Forwarded {
        private final Supplier<GrugControlServer> server;
        private final Consumer<String> failed;

        public Forwarded(Supplier<GrugControlServer> server, Consumer<String> failed) {
            this.server = server;
            this.failed = failed;
        }

        /**
         * The joined client's light for the section the position falls in compared with {@code
         * simulated}, which is the same fingerprint the server read from its own world.
         *
         * <p>A client that answers -1 cannot verify its copy, and that answer is treated as true
         * rather than as a mismatch: a loader whose client cannot reproduce the server's light
         * (1.2.5's incremental engine is history-dependent) would otherwise wait forever on a
         * comparison that is not the client's fault. An ask that could not be answered is also
         * true, which the failure reports as the host function's error.
         */
        public boolean lightApplied(double x, double y, double z, long simulated) {
            try {
                long client = (long) server.get().clientLightFingerprint(x, y, z);
                return client < 0 || client == simulated;
            } catch (GrugControlServer.ControlException e) {
                failed.accept("Test.light_applied: " + e.getMessage());
                return true;
            }
        }
    }
}
