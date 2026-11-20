package net.grug.minecraft.core;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The client-only test calls that are not a screen, an overlay or a capture, which a dedicated
 * server forwards to the joined client of the player who started the run (#1040): the mining crack
 * a client's frame pass draws, the selection box its game outlines a block with, and the three
 * calls of a hot reload of a client resource.
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

        /**
         * One of the client's normalization answers (see {@code
         * GrugControlWire.CALL_NORMALIZATION}), false when it could not be asked, which the runner
         * reports as a hook that did not apply.
         */
        public boolean normalization(int operation) {
            try {
                return server.get().normalization(operation);
            } catch (GrugControlServer.ControlException e) {
                failed.accept("The forced normalization: " + e.getMessage());
                return false;
            }
        }

        /** Whether the client's frame draws the crack over the block, false when not asked. */
        public boolean miningCrack(double x, double y, double z) {
            try {
                return server.get().miningCrack(x, y, z);
            } catch (GrugControlServer.ControlException e) {
                failed.accept("TestTickContext.mining_crack: " + e.getMessage());
                return false;
            }
        }

        /**
         * Whether a client that started a run is connected, so the calls below have someone to ask.
         * A server whose own game cannot answer a call asks this before it forwards one, and does
         * the call's own refusal when the answer is false (#1441).
         */
        public boolean hasJoinedClient() {
            return server.get().hasStarter();
        }

        /**
         * Whether the box the joined client's game targets and outlines for the block contains the
         * block-local point, false when it could not be asked (#1441).
         */
        public boolean blockSelectionContains(
                double x, double y, double z, double pointX, double pointY, double pointZ) {
            try {
                return server.get().blockSelectionContains(x, y, z, pointX, pointY, pointZ);
            } catch (GrugControlServer.ControlException e) {
                failed.accept("TestTickContext.block_selection: " + e.getMessage());
                return false;
            }
        }

        /** Puts the client's copy of a mod file into a state, and reports a failure. */
        public void modFileState(String path, String state) {
            int index = net.grug.minecraft.grug.GrugModFileStates.indexOf(state);
            if (index < 0) {
                failed.accept("TestTickContext.set_mod_file_state: unknown state " + state);
                return;
            }
            try {
                server.get().modFileState(path, index);
            } catch (GrugControlServer.ControlException e) {
                failed.accept("TestTickContext.set_mod_file_state: " + e.getMessage());
            }
        }

        /** The changes the client's engine has reported for the file, 0 when not asked. */
        public double hotReloadCount(String path) {
            try {
                return server.get().reloadCount(path, false);
            } catch (GrugControlServer.ControlException e) {
                failed.accept("TestTickContext.hot_reload_count: " + e.getMessage());
                return 0;
            }
        }

        /** The reloads the client's loader has finished for the file, 0 when not asked. */
        public double reloadCount(String path) {
            try {
                return server.get().reloadCount(path, true);
            } catch (GrugControlServer.ControlException e) {
                failed.accept("TestTickContext.reload_count: " + e.getMessage());
                return 0;
            }
        }
    }

    /**
     * Whether the box the game targets and outlines for the block at the position contains the
     * block-local point, which is {@code TestTickContext.block_selection} (#1441).
     *
     * <p>A loader whose own block class has no selection box, which is 1.2.5's dedicated server,
     * asks the joined client for it, as long as a client that started a run is connected. Every
     * other loader answers from its own game, on a server as on a client: the server's copy of the
     * world is the one its tests built, and a forwarded read would take the client's copy without
     * the flush a forwarded call makes before it. With no joined client the adapter's own call
     * runs, which refuses the way it did before there was a client to ask.
     */
    public static boolean blockSelectionContains(
            ModLoaderAdapter adapter,
            Object levelObj,
            double x,
            double y,
            double z,
            double pointX,
            double pointY,
            double pointZ) {
        Forwarded forwarded = adapter.forwardedClientTestCalls();
        if (adapter.blockSelectionIsClientOnly()
                && forwarded != null
                && forwarded.hasJoinedClient()) {
            return forwarded.blockSelectionContains(x, y, z, pointX, pointY, pointZ);
        }
        return adapter.blockSelectionContains(levelObj, x, y, z, pointX, pointY, pointZ);
    }
}
