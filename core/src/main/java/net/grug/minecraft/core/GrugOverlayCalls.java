package net.grug.minecraft.core;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The overlay reads a test makes, in one place, so the client that draws the overlay itself and the
 * client a dedicated server forwards the read to answer the same way (#1099).
 *
 * <p>An overlay is drawn by a client's own frame pass, so a server's answer would be about an
 * overlay no process ever drew: {@code GrugOverlay.overlaySupported} stays false there and the
 * drawn mark is never set. {@link Forwarded} is the server's end, which asks the client of the
 * player who started the run, as {@link GrugScreenCalls.Forwarded} asks it about the screen.
 */
public final class GrugOverlayCalls {
    @net.grug.minecraft.grug.GrugGenerated("utility class: never instantiated")
    private GrugOverlayCalls() {}

    /**
     * The overlay reads of a dedicated server, which has no overlay of its own: each one is
     * answered by the client of the player who started the run, and a failure is reported as the
     * host function's error, naming the function that failed.
     *
     * <p>The server comes in as a supplier rather than as a server, because a loader creates it on
     * the first use and the adapter that holds this object outlives that.
     */
    public static final class Forwarded {
        private final Supplier<GrugControlServer> server;
        private final Consumer<String> failed;

        public Forwarded(Supplier<GrugControlServer> server, Consumer<String> failed) {
            this.server = server;
            this.failed = failed;
        }

        /** Whether the client draws overlays at all, false when it could not be asked. */
        public boolean supported() {
            try {
                return server.get().overlaySupported();
            } catch (GrugControlServer.ControlException e) {
                failed.accept("Test.overlay_supported: " + e.getMessage());
                return false;
            }
        }

        /**
         * Whether the client has drawn the overlay of the mod that owns the running test, false
         * when it could not be asked.
         */
        public boolean drawn(String mod) {
            try {
                return server.get().overlayDrawn(mod);
            } catch (GrugControlServer.ControlException e) {
                failed.accept("Test.overlay_drawn: " + e.getMessage());
                return false;
            }
        }
    }
}
