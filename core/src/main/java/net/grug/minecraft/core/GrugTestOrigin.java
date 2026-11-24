package net.grug.minecraft.core;

import net.grug.minecraft.grug.Vec3;

/**
 * Where the origin {@code TestTickContext.get_origin} answers comes from: the test player's eye,
 * which is the render camera, lifted by {@link #LIFT} on every loader.
 *
 * <p>The rule lives here rather than in each adapter so the five loaders cannot drift apart, and so
 * a unit test reaches it without a game. What a loader still answers is its player's eye, through
 * {@link ModLoaderAdapter#testPlayerEye(Object)}, because only that loader's mappings say whether
 * the position it reads is the eye (every client before 1.20.6) or the feet (1.20.6, whose {@code
 * getY} is the feet, and the a1.1.2_01 and b1.7.3 servers, whose movement handler stores the
 * client's feet in {@code y} and which therefore add the eye height through {@link #eyeOfFeet}).
 *
 * <p>Adding the lift to the eye, rather than to the feet, is what keeps a fixture and a capture on
 * the same row: the camera is the eye, so a fixture this far above it is the same distance from
 * every loader's viewpoint. See #609.
 */
public final class GrugTestOrigin {
    private GrugTestOrigin() {}

    /**
     * How far above the test player's eye the bands begin. Three blocks is where the bands already
     * sit in every test, so moving the arithmetic here must not move them.
     */
    public static final double LIFT = 3.0;

    /**
     * How far the eye sits above the feet on every loader.
     *
     * <p>It is the game's own {@code float} 1.62 widened to a double, 1.6200000047683716, and not
     * the decimal 1.62. Every version stores the eye as {@code feet + (double) 1.62F}: 1.2.5 adds
     * its {@code yOffset}, 1.20.6 adds its {@code eyeHeight} in {@code getEyeY}, and the old
     * versions' {@code setPositionAndAngles} does the same. A decimal 1.62 therefore reports an eye
     * 5e-9 under the client's, which changes a joined client's camera bits and draws a line's edge
     * pixel differently (#1629).
     *
     * <p>This is the only copy. The origin, a server's rebuilt eye and a joined capture pin all
     * read it, so the three cannot drift apart again (#1662).
     */
    public static final double EYE_HEIGHT = (double) 1.62F;

    /**
     * The eye a client whose feet are at {@code feetY} stands at: the feet plus {@link
     * #EYE_HEIGHT}. A teleport packet carries it for such a client, whose box bottom {@code eye -
     * EYE_HEIGHT} is the feet exactly (the unit test checks it over many heights, because a box
     * bottom under the feet sinks the client through the floor it stands on, #1688).
     */
    public static double clientEye(double feetY) {
        return feetY + EYE_HEIGHT;
    }

    /**
     * The feet under an eye at {@code eyeY}: {@link #clientEye(double)}'s inverse. A loader whose
     * {@code setPositionAndAngles} takes the feet subtracts this before passing them, and the game
     * adds the same height back, so the pinned camera is the eye the capture named (#1662).
     *
     * <p>The subtraction recovers the feet bit for bit when the game's own addition was exact,
     * which an eighth-block grid and every feet value a test room is built from are. It is not
     * exact for an arbitrary double: the sum can round its lowest set bit away before this
     * subtracts the height, and 1.7 comes back one unit in the last place of the sum high, 2.2e-16,
     * which puts the pinned camera that far from the eye rather than on it. The eye direction needs
     * no such caveat: {@link #clientEye(double)} is the same widening and the same addition the
     * game does, so it names the game's own eye for every feet value.
     */
    public static double feetOfEye(double eyeY) {
        return eyeY - EYE_HEIGHT;
    }

    /**
     * The origin from the test player's eye. There is no null case here, because the one caller
     * decides it: {@link ModLoaderAdapter#getTestOrigin()} answers null for a side with no player
     * without asking a loader for an eye it cannot have.
     */
    public static Vec3 of(Vec3 eye) {
        return new Vec3(eye.x(), eye.y() + LIFT, eye.z());
    }

    /**
     * The eye above a room's feet anchor, which is the position a capture has to be drawn from and
     * the point {@link #of(Vec3)} lifts: a loader whose player keeps the feet in its position reads
     * this instead of the position itself (#1178).
     */
    public static Vec3 eyeOfFeet(double x, double feetY, double z) {
        return new Vec3(x, clientEye(feetY), z);
    }

    /**
     * The eye an origin was measured from, which is what a capture request names: the room a test
     * builds is placed around the origin, so its camera has to be this point rather than wherever
     * the joined client's view happens to be (#1161).
     */
    public static Vec3 eyeOf(Vec3 origin) {
        return new Vec3(origin.x(), origin.y() - LIFT, origin.z());
    }
}
