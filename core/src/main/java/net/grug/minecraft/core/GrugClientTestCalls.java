package net.grug.minecraft.core;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The client-only test calls that are not a screen, an overlay or a capture, which a dedicated
 * server forwards to the joined client of the player who started the run (#1040): the mining crack
 * a client's frame pass draws, and the three calls of a hot reload of a client resource.
 *
 * <p>A texture is read by the client alone, so a server that disturbed its own copy of the file
 * would reload nothing and a count it kept would never rise. The client's own mods directory holds
 * the file the client's watcher polls, so the state is set there and the counts are read there. The
 * server's end comes in as a supplier, as {@link GrugOverlayCalls.Forwarded} does, because a loader
 * creates its control server on first use.
 */
public final class GrugClientTestCalls {
    @net.grug.minecraft.grug.GrugGenerated("utility class: never instantiated")
    private GrugClientTestCalls() {}

    /** The server's end of those calls. A failure is reported as the host function's error. */
    public static final class Forwarded {
        private final Supplier<GrugControlServer> server;
        private final Consumer<String> failed;

        public Forwarded(Supplier<GrugControlServer> server, Consumer<String> failed) {
            this.server = server;
            this.failed = failed;
        }

        /** Whether the client's frame draws the crack over the block, false when not asked. */
        public boolean miningCrack(double x, double y, double z) {
            try {
                return server.get().miningCrack(x, y, z);
            } catch (GrugControlServer.ControlException e) {
                failed.accept("Test.mining_crack: " + e.getMessage());
                return false;
            }
        }

        /** Puts the client's copy of a mod file into a state, and reports a failure. */
        public void modFileState(String path, String state) {
            int index = net.grug.minecraft.grug.GrugModFileStates.indexOf(state);
            if (index < 0) {
                failed.accept("Test.set_mod_file_state: unknown state " + state);
                return;
            }
            try {
                server.get().modFileState(path, index);
            } catch (GrugControlServer.ControlException e) {
                failed.accept("Test.set_mod_file_state: " + e.getMessage());
            }
        }

        /** The changes the client's engine has reported for the file, 0 when not asked. */
        public double hotReloadCount(String path) {
            try {
                return server.get().reloadCount(path, false);
            } catch (GrugControlServer.ControlException e) {
                failed.accept("Test.hot_reload_count: " + e.getMessage());
                return 0;
            }
        }

        /** The reloads the client's loader has finished for the file, 0 when not asked. */
        public double reloadCount(String path) {
            try {
                return server.get().reloadCount(path, true);
            } catch (GrugControlServer.ControlException e) {
                failed.accept("Test.reload_count: " + e.getMessage());
                return 0;
            }
        }
    }
}
