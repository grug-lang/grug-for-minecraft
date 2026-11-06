package net.grug.minecraft.core;

import net.grug.minecraft.grug.Vec3;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Whether a frame a run drew has to be counted busy because the mouse moved the player's view.
 *
 * <p>A run owns the camera: the runner levels the player's view when a test starts, no test can
 * turn it, and a captured frame has to show that view. The mouse can still turn it during a run,
 * because closing a screen grabs the cursor and the warp back to the window's centre is reported to
 * the game as a movement on a later frame. That is about 80 degrees of yaw and 45 to 54 of pitch on
 * Alpha at the default sensitivity, so a capture then shows the room from wherever the cursor left
 * the view rather than the settled scene the test built. See #1128.
 *
 * <p>The frame hook samples the view at the end of the frame, where the game has already read the
 * mouse for it, and puts the view back for the next frame. The frame the turn landed in is the one
 * the sample calls moved, so the loader counts it as busy and the frame a capture reads is one
 * drawn after the view was level again.
 *
 * <p>The decision is a pure function of the values the loader reads, so its unit test covers every
 * combination. The loaders carry no branch whose arm a run only meets when a warp happens to land
 * inside the coverage tests, and the level write is made on every frame of a run rather than only
 * on a frame a move was read, so every run reaches that write too.
 */
public final class GrugTestCamera {
    private GrugTestCamera() {}

    /**
     * A loader's view of its local player: the four angles a frame's camera comes from, and the
     * write that levels them.
     *
     * <p>The four are this generation's own fields or accessors, so a loader passes the player it
     * already chose and reads and writes it here without carrying a branch of its own.
     */
    public interface View {
        /** The player's yaw. */
        float yaw(Object player);

        /** The player's pitch. */
        float pitch(Object player);

        /** The yaw a frame interpolates the camera from. */
        float previousYaw(Object player);

        /** The pitch a frame interpolates the camera from. */
        float previousPitch(Object player);

        /** Sets the view to the level one, so the next frame is drawn from it. */
        void level(Object player);
    }

    /**
     * Whether the player's view is not the level one the runner snapped, given what the loader read
     * at the end of a frame. Pure, so its unit test covers every combination.
     *
     * @param viewOwned whether the runner holds the player's view, from a test's snap to its
     *     restore
     * @param playerPresent whether the loader has a local player whose view it can read
     */
    public static boolean viewMoved(
            boolean viewOwned,
            boolean playerPresent,
            float yaw,
            float pitch,
            float previousYaw,
            float previousPitch) {
        return viewOwned
                && playerPresent
                && (yaw != 0.0F || pitch != 0.0F || previousYaw != 0.0F || previousPitch != 0.0F);
    }

    /**
     * Puts the player's view back when the runner owns it, and answers whether the frame that just
     * finished was drawn from a view the mouse had moved.
     *
     * <p>The view is written whenever the runner owns it, not only when it had moved: a level write
     * over an already level view keeps the write on every frame of a run, so no coverage arm
     * depends on a warp landing at the right moment. The answer is still the move, so only the
     * frame a turn landed in is counted as busy.
     */
    public static boolean levelForFrame(boolean viewOwned, Object player, View view) {
        if (player == null) return false;
        boolean moved =
                viewMoved(
                        viewOwned,
                        true,
                        view.yaw(player),
                        view.pitch(player),
                        view.previousYaw(player),
                        view.previousPitch(player));
        if (viewOwned) {
            view.level(player);
        }
        return moved;
    }

    /**
     * Whether the frame that just finished is one a capture may not read: it still had work in it,
     * or it was drawn from a view the mouse had moved.
     */
    public static boolean frameBusy(
            boolean frameHadPendingWork, boolean viewOwned, Object player, View view) {
        // Levelled first, so a frame with work left is levelled for the next one too; the answer
        // combines both reasons a capture may not read the frame.
        boolean moved = levelForFrame(viewOwned, player, view);
        return frameHadPendingWork || moved;
    }

    /**
     * How far a client's own camera may be from the eye a capture names and still be that view, in
     * blocks.
     *
     * <p>The two come from the same server and client player, so a match is the same place to
     * within the noise of two processes integrating a tick each: a thousandth of a block is a tenth
     * of a pixel at the distance a test's fixture sits, and anything larger is the client not
     * having arrived yet rather than rounding. See #1161.
     */
    public static final double CAPTURE_EYE_TOLERANCE = 1.0 / 1024;

    /**
     * Whether {@code camera} is the eye {@code eye}, so a capture taken now is drawn from the place
     * the test's room was built above.
     *
     * <p>A side with no camera yet, or a request that named no eye, is never at it: a capture waits
     * for both rather than reading a frame nobody has.
     */
    public static boolean cameraAt(Vec3 camera, Vec3 eye) {
        if (camera == null || eye == null) return false;
        return Math.abs(camera.x() - eye.x()) <= CAPTURE_EYE_TOLERANCE
                && Math.abs(camera.y() - eye.y()) <= CAPTURE_EYE_TOLERANCE
                && Math.abs(camera.z() - eye.z()) <= CAPTURE_EYE_TOLERANCE;
    }

    /**
     * One component of a camera position, 0 for x, 1 for y and 2 for z, which is what a forwarded
     * {@link GrugControlWire#CALL_CAMERA} asks for.
     *
     * <p>It throws rather than answering 0 for an axis a caller invented, and for a side with no
     * player: the forwarded call turns that into an error answer, so a test sees why its camera
     * could not be read instead of a position the game never had.
     */
    public static double axis(Vec3 camera, int axis) {
        if (camera == null) {
            throw new IllegalStateException("this side has no test player to read a camera from");
        }
        switch (axis) {
            case 0:
                return camera.x();
            case 1:
                return camera.y();
            case 2:
                return camera.z();
            default:
                throw new IllegalArgumentException("there is no camera axis " + axis);
        }
    }

    /**
     * The camera calls of a dedicated server, which has no camera of its own: the joined client of
     * the player who started the run reports its own, which is the view a capture is drawn from
     * (#1161).
     *
     * <p>The server comes in as a supplier rather than as a server, because a loader creates it on
     * the first use and the adapter that holds this object outlives that, as with the screen calls.
     */
    public static final class Forwarded {
        private final Supplier<GrugControlServer> server;
        private final Consumer<String> failed;

        public Forwarded(Supplier<GrugControlServer> server, Consumer<String> failed) {
            this.server = server;
            this.failed = failed;
        }

        /**
         * The joined client's own camera, or null when it could not be asked, which the failure
         * reports as the host function's error.
         */
        public Vec3 camera() {
            try {
                return new Vec3(
                        server.get().camera(0), server.get().camera(1), server.get().camera(2));
            } catch (GrugControlServer.ControlException e) {
                failed.accept("Test.camera: " + e.getMessage());
                return null;
            }
        }
    }
}
