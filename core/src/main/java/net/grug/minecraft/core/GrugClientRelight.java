package net.grug.minecraft.core;

/**
 * When a joined client sweeps its own copy of the test room's light, and over which box (#1213).
 *
 * <p>1.2.5 sends no light packets: the server relights the room by sweeping its copy ({@link
 * GrugRelight}), while the joined client keeps the light its incremental engine reached from the
 * block changes it saw, in the order it saw them. A test that leaves a torch lit and a room rebuilt
 * around it can leave the client's copy brighter at one side than the field the blocks define, and
 * a capture then differs from its golden over a smooth gradient. The comparison of #1240 cannot
 * catch it, because the client's copy is not expected to equal the server's there.
 *
 * <p>The client therefore recomputes its own copy of the room once the capture's wait has the frame
 * it would read, and waits for the frames that show the result. Pure, so a unit test covers every
 * combination of the decision and the box.
 */
public final class GrugClientRelight {
    /** Blocks around the eye the sweep covers on x and z: wider than any room a test builds. */
    static final int HORIZONTAL = 10;

    /** Blocks below the eye the sweep covers: the room's wall bottom sits well under the floor. */
    static final int BELOW = 10;

    /** Blocks above the eye the sweep covers: the room's ceiling is a diameter above the floor. */
    static final int ABOVE = 20;

    private GrugClientRelight() {}

    /**
     * Whether the client sweeps its copy now: when the frame the capture would read is ready and
     * this capture's sweep has not run. A capture that starts waiting resets {@code alreadySwept},
     * so every capture is read from a field recomputed after the blocks it shows arrived.
     */
    public static boolean sweepNow(boolean frameReady, boolean alreadySwept) {
        return frameReady && !alreadySwept;
    }

    /** The box to sweep around an eye, as minX, minY, minZ, maxX, maxY, maxZ in blocks. */
    public static int[] box(double eyeX, double eyeY, double eyeZ) {
        int x = (int) Math.floor(eyeX);
        int y = (int) Math.floor(eyeY);
        int z = (int) Math.floor(eyeZ);
        return new int[] {
            x - HORIZONTAL, y - BELOW, z - HORIZONTAL, x + HORIZONTAL, y + ABOVE, z + HORIZONTAL
        };
    }
}
