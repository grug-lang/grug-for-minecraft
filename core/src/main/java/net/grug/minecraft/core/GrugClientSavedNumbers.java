package net.grug.minecraft.core;

import net.grug.minecraft.grug.GrugSavedNumbers;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A test's read of the joined client's own copy of a block entity's saved number (#1162).
 *
 * <p>A test runs on the side that simulates the world, and on a dedicated server that side is the
 * server, whose copy is not the one the joined client's frame pass reads. A capture can show that
 * the frame changed, but not whether the data arrived or something else moved the frame, and a
 * capture is a frame behind the packet that arrives during the wait. This read answers the one fact
 * a save-verifying test cannot infer: whether the client's copy holds the number the test saved.
 *
 * <p>Two halves share the class, as {@link GrugScreenCalls} does. The static {@link #read} is the
 * call on the side that holds the copy, which is a client: both {@code HostFunctions} and the
 * forwarded answer of {@link GrugControlClient} go through it, so a singleplayer read and a
 * forwarded one cannot differ. {@link Forwarded} is the other end, the read a dedicated server
 * makes of the client of the player who started the run.
 */
public final class GrugClientSavedNumbers {

    private GrugClientSavedNumbers() {}

    /**
     * The number saved under {@code name} by this process's own copy of the block entity at the
     * position, 0 when this process holds no such block entity or the number was never saved.
     *
     * <p>The level is the one {@link ModLoaderAdapter#clientLevel()} answers, which is the client's
     * own copy, the one the frame pass draws. It is not {@link ModLoaderAdapter#getLevel()}, which
     * on 1.20.6 in singleplayer is the integrated server's world, the copy a fixture is placed in.
     */
    public static double read(ModLoaderAdapter adapter, double x, double y, double z, String name) {
        Object level = adapter.clientLevel();
        if (level == null) {
            return 0.0;
        }
        return GrugSavedNumbers.get(adapter.getBlockEntity(level, x, y, z), name);
    }

    /**
     * The reads a dedicated server makes, which the joined client of the player who started the run
     * answers: a server holds the simulated copy, not the one that client's frame pass reads.
     *
     * <p>The server comes in as a supplier rather than as a server, because a loader creates it on
     * the first use and the adapter that holds this object outlives that, as with the screen and
     * camera calls.
     */
    public static final class Forwarded {
        private final Supplier<GrugControlServer> server;
        private final Consumer<String> failed;

        public Forwarded(Supplier<GrugControlServer> server, Consumer<String> failed) {
            this.server = server;
            this.failed = failed;
        }

        /**
         * The joined client's number, or 0 when it could not be asked, which the failure reports as
         * the host function's error.
         */
        public double savedNumber(double x, double y, double z, String name) {
            try {
                return server.get().savedNumber(x, y, z, name);
            } catch (GrugControlServer.ControlException e) {
                failed.accept("Test.client_saved_number: " + e.getMessage());
                return 0.0;
            }
        }
    }
}
