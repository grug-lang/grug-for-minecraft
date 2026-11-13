package net.grug.minecraft.core;

/**
 * Where the test runner puts the player before every test.
 *
 * <p>The runner snaps the player to the centre of the block they stand in and levels their view
 * before the room is built, so every test starts with its player in a known place and looking the
 * same way, whatever the previous test left behind. The snap stays inside the player's own block,
 * and therefore inside the chunk already loaded and lit around them, because moving into a chunk
 * that is not lit yet crashes Alpha. See #609.
 *
 * <p>Both sides snap, but not the same player: a client moves its own local player, while a
 * dedicated server moves its copy of the joined player and lets the teleport packet carry the
 * joined client's camera and view with it. The levelling therefore reaches a joined client as a
 * rotation in that packet rather than as a local write. A server that skipped its half built its
 * room from wherever the client happened to stand, off the block grid a capture is framed against
 * (#1161).
 *
 * <p>Each side saves what it found before it moves the player and puts it back when the test ends,
 * so a test that moved the player, including one that failed, leaves the next test the same player
 * it inherited. A client keeps that state in its adapter, beside its own player; a dedicated server
 * keeps it in {@link ServerSave}, because the adapter's half of the restore has no player object to
 * save into when the snap runs.
 *
 * <p>The arithmetic and the server's saved state live here rather than in each loader so the five
 * adapters cannot drift apart, and so a unit test can reach them without a game.
 */
public final class GrugTestPlayerSnap {
    private GrugTestPlayerSnap() {}

    /**
     * The vertical velocity a player standing still on the ground has after each of its ticks: the
     * gravity step Alpha and Beta apply after the move, {@code (0 - 0.08) * 0.98}, which the floor
     * then cancels in the next move.
     *
     * <p>A client that pins its player at a capture's eye has to leave this value in the player,
     * not zero. Those versions tilt the first-person camera by {@code atan(-velocityY * 0.2) * 15}
     * degrees, smoothed from the velocity of every tick, so a standing player's view is tilted by
     * 0.235 degrees and a pinned player whose velocity was zeroed is not. That is a uniform 2 px
     * shift of the whole frame at 1280x720, and it was the whole residue between a joined client's
     * capture and the flat golden a client-only run recorded (#1213).
     */
    public static final double RESTING_VELOCITY_Y = (0.0 - 0.08) * 0.98F;

    /**
     * The coordinate the player is snapped to on one horizontal axis: the middle of the block they
     * stand in, so the room, the origin and a capture agree on where the player is.
     */
    public static double blockCentre(double coordinate) {
        return Math.floor(coordinate) + 0.5;
    }

    /**
     * A dedicated server's copy of the test player as they were before the snap, so the server can
     * put them back after the test.
     *
     * <p>It holds what the snap writes as well as what it found, so every loader's server half asks
     * the same object for both and the values cannot drift apart: the centre of the saved block on
     * x and z, the saved height (the floor hold owns that), and a level view.
     */
    public static final class ServerSave {
        private boolean saved;
        private Object owner;
        private double x;
        private double y;
        private double z;
        private float yaw;
        private float pitch;

        /**
         * Saves the player as the game reports them, before the snap moves them. {@code owner} is
         * the player object the values belong to, compared by reference in {@link #claimRestore},
         * so the saved position is never put on another player (#1308).
         */
        public void save(Object owner, double x, double y, double z, float yaw, float pitch) {
            this.owner = owner;
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.pitch = pitch;
            this.saved = true;
        }

        /**
         * Whether a snap happened since the last restore, so a restore before any snap is a no-op.
         */
        public boolean isSaved() {
            return saved;
        }

        /**
         * Whether {@code player} may take the saved state back. A restore with nothing saved is
         * refused and changes nothing. A restore for any other player than the one saved, or for
         * none because the owner just left, is refused and discards the snapshot, so the owner's
         * position and view are never applied to a player who fell in as the fallback (#1308).
         */
        public boolean claimRestore(Object player) {
            if (!saved) {
                return false;
            }
            if (player == null || player != owner) {
                clear();
                return false;
            }
            return true;
        }

        /** The x the snap writes: the centre of the block the player was saved in. */
        public double snappedX() {
            return blockCentre(x);
        }

        /**
         * The y the snap writes: the saved height, because the floor hold owns the player's height.
         */
        public double snappedY() {
            return y;
        }

        /** The z the snap writes: the centre of the block the player was saved in. */
        public double snappedZ() {
            return blockCentre(z);
        }

        /**
         * The yaw the snap writes, level like a client's half, so every loader frames a fixture
         * alike.
         */
        public float snappedYaw() {
            return 0.0F;
        }

        /**
         * The pitch the snap writes, level like a client's half, so every loader frames a fixture
         * alike.
         */
        public float snappedPitch() {
            return 0.0F;
        }

        /** The x the restore writes: exactly where the player stood before the snap. */
        public double savedX() {
            return x;
        }

        /** The y the restore writes: exactly the height the player stood at before the snap. */
        public double savedY() {
            return y;
        }

        /** The z the restore writes: exactly where the player stood before the snap. */
        public double savedZ() {
            return z;
        }

        /** The yaw the restore writes: exactly the view the player had before the snap. */
        public float savedYaw() {
            return yaw;
        }

        /** The pitch the restore writes: exactly the view the player had before the snap. */
        public float savedPitch() {
            return pitch;
        }

        /** Forgets the saved state, so the next restore is a no-op until another snap saves it. */
        public void clear() {
            this.saved = false;
            this.owner = null;
        }
    }
}
