package net.grug.minecraft.core;

/**
 * Where a client holds its player while a test runs, so a capture is drawn from the pose the room
 * was built around (#1580).
 *
 * <p>The snap puts the player at the centre of their block once, at the start of a test. Nothing
 * held them there afterwards, so whatever moves a player moved the camera: the mobcap test fills
 * the world with 200 mobs of a category, which crowd the player (entity pushes) and hit them
 * (knockback), and the a1 client's camera left the snap by 0.1 to 0.5 blocks and jumped in an arc
 * within a second of the fill. A texture drawn 0.5 blocks away lands on other pixels, so the dark
 * stone of a golden moved by one to three pixels from one run to the next while the text stayed
 * identical. A client that captures through a forwarded request already pins its player at the eye
 * every tick; this is the same pin for the tests that run in the client's own process.
 *
 * <p>The target is the pose the snap wrote, read back from the player rather than recomputed, so
 * the pin writes the client's own arithmetic. A test that moves the player on purpose ({@code
 * set_player_position}) moves the target with them. The loader asks {@link #target} on every client
 * tick and, when it answers, writes the pose and zeroes the velocity.
 *
 * <p>Volatile because the runner sets it from the thread that runs the test and a loader reads it
 * on the client tick, which may be another thread.
 */
public final class GrugTestPlayerPin {
    private static volatile Pose target = null;

    private GrugTestPlayerPin() {}

    /** A held position on the two horizontal axes. */
    public static final class Pose {
        public final double x;
        public final double z;

        private Pose(double x, double z) {
            this.x = x;
            this.z = z;
        }
    }

    /**
     * Holds the player at this horizontal position from the next client tick until {@link
     * #release}.
     */
    public static void hold(double x, double z) {
        target = new Pose(x, z);
    }

    /** The test ended, so the player is free to move and be restored. */
    public static void release() {
        target = null;
    }

    /** The pose to hold the player at this tick, or null when no test holds them. */
    public static Pose target() {
        return target;
    }
}
