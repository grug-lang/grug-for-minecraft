package net.grug.minecraft.core;

/**
 * Where a dedicated server should hold a player whose own client says it is unsupported.
 *
 * <p>A server starts a run only once the joined player stands on the ground, but it learns whether
 * the player is supported from the client's movement packets, and a client can be wrong. While the
 * chunks under it are still arriving, the client falls through the terrain; the server then keeps
 * teleporting it back to a position a fraction of a block inside the ground, where the server's own
 * collision check accepts it because the box it tests is contracted by 0.0625. From there the
 * client's collision never reports support, so it never sends {@code onGround}, and the wait for a
 * joined player times out even though the client is in the world and has started the run (#1095).
 *
 * <p>The ground below the player is in the server's own world, so the server can hold the player on
 * it instead of trusting the client. The rule is a pure function of the blocks the server reads, so
 * its unit test covers the height a real run got stuck at (70.94899540560743 above ground whose top
 * is 71) as well as a landed player, one inside a two-block stack and one over the void.
 */
public final class GrugPlayerFloor {
    /**
     * How far from the floor the feet may be and still count as standing on it. The client's own
     * arithmetic is off by about a millionth, and the server accepts a fall of up to 0.0625 into
     * the ground, so this sits far below both and still rejects the 0.051 the run got stuck at.
     */
    public static final double TOLERANCE = 0.01;

    /**
     * The lowest and highest blocks the scan reads. Both bounds are outside every supported
     * version's build height, so a level that answers air there ends the scan rather than the
     * bound; the top one is also what stops a level that never answers air.
     */
    private static final int SCAN_BOTTOM = -256;

    /** The top of the upward scan, package-private so its unit test can name the cap. */
    static final int SCAN_TOP = 512;

    private GrugPlayerFloor() {}

    /** Reads one block position from a level, so the floor does not depend on a loader. */
    public interface BlockSource {
        /**
         * Whether the block stops movement, which is the test the level's own surface height uses:
         * a torch, a plant or snow does not hold a player up.
         */
        boolean blocksMovement(int x, int y, int z);
    }

    /**
     * The top of the solid column under the feet: the first solid block at or below the feet, and
     * then every solid block directly above it, so a player inside a stack is put on top of the
     * stack rather than inside its next block.
     *
     * @return the top of that column, or {@link Double#NEGATIVE_INFINITY} when there is no solid
     *     block below the feet, so a player over the void is left where they are
     */
    public static double floorTop(BlockSource blocks, double x, double feetY, double z) {
        int blockX = (int) Math.floor(x);
        int blockZ = (int) Math.floor(z);
        for (int y = (int) Math.floor(feetY); y >= SCAN_BOTTOM; y--) {
            if (!blocks.blocksMovement(blockX, y, blockZ)) {
                continue;
            }
            int top = y;
            while (top < SCAN_TOP && blocks.blocksMovement(blockX, top + 1, blockZ)) {
                top++;
            }
            return top + 1.0;
        }
        return Double.NEGATIVE_INFINITY;
    }

    /**
     * Whether the feet are below the floor, which is when the server puts the player back on it. A
     * player exactly on it, or a millionth above it, is left alone so the hold cannot fight the
     * client's own position.
     */
    public static boolean below(double feetY, double floorTop) {
        return feetY < floorTop;
    }

    /**
     * Whether the feet are on the floor, within {@link #TOLERANCE}. A player below it is inside the
     * ground and one above it is still falling, and neither may start a run.
     */
    public static boolean standsOn(double feetY, double floorTop) {
        return Math.abs(feetY - floorTop) <= TOLERANCE;
    }

    /**
     * Whether the feet are inside a solid block, which is never a state a test may start in: the
     * room would be built around the wrong height and a forwarded capture would see the inside of
     * the block. A player standing on a block top is not inside it, because the block above their
     * feet is air.
     */
    public static boolean insideABlock(BlockSource blocks, double x, double feetY, double z) {
        return blocks.blocksMovement(
                (int) Math.floor(x), (int) Math.floor(feetY), (int) Math.floor(z));
    }
}
