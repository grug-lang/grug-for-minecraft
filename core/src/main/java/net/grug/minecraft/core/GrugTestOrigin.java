package net.grug.minecraft.core;

import net.grug.minecraft.grug.Vec3;

/**
 * Where the origin {@code Test.get_origin} answers comes from: the test player's eye, which is the
 * render camera, lifted by {@link #LIFT} on every loader.
 *
 * <p>The rule lives here rather than in each adapter so the five loaders cannot drift apart, and so
 * a unit test reaches it without a game. What a loader still answers is its player's eye, through
 * {@link ModLoaderAdapter#testPlayerEye(Object)}, because only that loader's mappings say whether
 * the position it reads is the eye (every version before 1.20.6) or the feet (1.20.6, whose {@code
 * getEyeY} adds the eye height).
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
     * The origin from the test player's eye. There is no null case here, because the one caller
     * decides it: {@link ModLoaderAdapter#getTestOrigin()} answers null for a side with no player
     * without asking a loader for an eye it cannot have.
     */
    public static Vec3 of(Vec3 eye) {
        return new Vec3(eye.x(), eye.y() + LIFT, eye.z());
    }
}
