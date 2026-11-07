package net.grug.minecraft.core;

import net.grug.minecraft.grug.Vec3;

/**
 * Where the origin {@code Test.get_origin} answers comes from: the test player's eye, which is the
 * render camera, lifted by {@link #LIFT} on every loader.
 *
 * <p>The rule lives here rather than in each adapter so the five loaders cannot drift apart, and so
 * a unit test reaches it without a game. What a loader still answers is its player's eye, through
 * {@link ModLoaderAdapter#testPlayerEye(Object)}, because only that loader's mappings say whether
 * the position it reads is the eye (every client before 1.20.6, and the a1.1.2_01 server, whose
 * player keeps the eye in {@code y}) or the feet (1.20.6, whose {@code getEyeY} adds the eye
 * height, and the b1.7.3 server, which adds it through {@link #eyeOfFeet}).
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
     * How far the eye sits above the feet on every loader. The room a test builds is anchored at
     * the feet, so a server whose player keeps the feet in its position has to add this to report
     * the eye the room was built around (#1178).
     */
    public static final double EYE_HEIGHT = 1.62;

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
        return new Vec3(x, feetY + EYE_HEIGHT, z);
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
