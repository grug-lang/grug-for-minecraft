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
 *
 * <p>The floor is the top of the block's <em>collision shape</em>, not its full cube: a slab at
 * y=70 stops a player at 70.5 and soul sand at 70.875, so reading the block's top instead would
 * lift a player standing on one by the missing part, and treating the whole block as solid would
 * reject a player whose feet are exactly on that shape (#1102).
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
         * The top of the highest collision box at a block position that covers the given point,
         * measured above the block's own bottom, or {@link #NO_COLLISION} when no box there covers
         * it.
         *
         * <p>The point is the player's own column, because one block can hold more than one box: a
         * stair's lower step is half a block high and its upper one is a full block, so which of
         * them is the floor depends on where the player stands. Every collision box this answers
         * for starts at the block's bottom, so its top is all the floor needs.
         */
        double collisionTop(int x, int y, int z, double pointX, double pointZ);

        /**
         * The answer for a block with no collision box, such as air, a plant or a torch: it is not
         * a floor and a player can stand in it.
         */
        double NO_COLLISION = Double.NEGATIVE_INFINITY;
    }

    /**
     * The top of the collision column under the feet: the highest collision box at the player's
     * point in the first block at or below the feet that has one, and then the top of every block
     * directly above it that has one too, so a player inside a stack is put on top of the stack
     * rather than inside its next block.
     *
     * @return the top of that column, or {@link Double#NEGATIVE_INFINITY} when there is no
     *     collision box below the feet, so a player over the void is left where they are
     */
    public static double floorTop(BlockSource blocks, double x, double feetY, double z) {
        int blockX = (int) Math.floor(x);
        int blockZ = (int) Math.floor(z);
        for (int y = (int) Math.floor(feetY); y >= SCAN_BOTTOM; y--) {
            double top = blocks.collisionTop(blockX, y, blockZ, x, z);
            if (top == BlockSource.NO_COLLISION) {
                continue;
            }
            double floor = y + top;
            int above = y + 1;
            while (above <= SCAN_TOP) {
                double aboveTop = blocks.collisionTop(blockX, above, blockZ, x, z);
                if (aboveTop == BlockSource.NO_COLLISION) {
                    break;
                }
                floor = above + aboveTop;
                above++;
            }
            return floor;
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
     * Whether the feet are inside a block's collision shape, which is never a state a test may
     * start in: the room would be built around the wrong height and a forwarded capture would see
     * the inside of the block. A player standing on a shape's top is not inside it, within the same
     * {@link #TOLERANCE} that {@link #standsOn} uses, so a slab's top at 70.5 is stood on and not
     * fallen through.
     */
    public static boolean insideABlock(BlockSource blocks, double x, double feetY, double z) {
        int blockY = (int) Math.floor(feetY);
        double top = blocks.collisionTop((int) Math.floor(x), blockY, (int) Math.floor(z), x, z);
        return top != BlockSource.NO_COLLISION && feetY < blockY + top - TOLERANCE;
    }
}
