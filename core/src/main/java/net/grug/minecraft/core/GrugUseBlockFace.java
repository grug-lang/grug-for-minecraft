package net.grug.minecraft.core;

import net.grug.minecraft.grug.Vec3;

/**
 * Which face of a block a test's right click lands on, chosen from where the test player's eye is.
 *
 * <p>A real click sends the face its ray trace entered, and a test has no camera to aim: it names
 * the block it wants clicked instead. The face is therefore the one a ray from the test player's
 * eye to that block's centre enters, which is the face the player sees of the block. A block below
 * the eye is clicked on its top, one above it on its bottom, and one beside it at the eye's level
 * on the side facing the player. Both sides of a test run the same rule, so a dedicated server
 * clicks what the client clicks for the same fixture (#1106).
 *
 * <p>The face is a number in the order every loader before 1.20.6 hands its interaction manager,
 * which is also the order of 1.20.6's {@code Direction}: {@code 0} is down, {@code 1} up, {@code 2}
 * north ({@code -z}), {@code 3} south ({@code +z}), {@code 4} west ({@code -x}) and {@code 5} east
 * ({@code +x}).
 *
 * <p>The ray travels furthest along one axis, so that axis decides which slab of the block's box it
 * crosses first, and therefore the face. A ray into an edge or a corner is equally on both faces it
 * touches, so the order the axes are checked in ({@code y}, then {@code x}, then {@code z}) decides
 * it, the same way every time.
 */
public final class GrugUseBlockFace {

    /** The block's {@code -y} face. */
    public static final int DOWN = 0;

    /** The block's {@code +y} face. */
    public static final int UP = 1;

    /** The block's {@code -z} face. */
    public static final int NORTH = 2;

    /** The block's {@code +z} face. */
    public static final int SOUTH = 3;

    /** The block's {@code -x} face. */
    public static final int WEST = 4;

    /** The block's {@code +x} face. */
    public static final int EAST = 5;

    private GrugUseBlockFace() {}

    /**
     * The face a ray from {@code eye} to the centre of the block at the given position enters.
     *
     * <p>A ray that travels towards {@code +y} enters the block's bottom face and one towards
     * {@code -y} its top, and the same holds for the other axes, so the face is the one whose
     * outward normal points back along the axis the ray travels furthest on.
     */
    public static int face(Vec3 eye, int blockX, int blockY, int blockZ) {
        double dx = blockX + 0.5 - eye.x();
        double dy = blockY + 0.5 - eye.y();
        double dz = blockZ + 0.5 - eye.z();
        double adx = Math.abs(dx);
        double ady = Math.abs(dy);
        double adz = Math.abs(dz);
        if (ady >= adx && ady >= adz) {
            return dy > 0.0 ? DOWN : UP;
        }
        if (adx >= adz) {
            return dx > 0.0 ? WEST : EAST;
        }
        return dz > 0.0 ? NORTH : SOUTH;
    }
}
