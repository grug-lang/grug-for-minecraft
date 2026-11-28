package net.grug.minecraft.ornithe.client;

/**
 * Test only: the GUI scale a test forces on a version that has no GUI scale option, and the scale
 * that version's own render window would compute for a framebuffer.
 *
 * <p>Alpha 1.1.2_01 has no option for this: every {@code Window} works the factor out from the
 * framebuffer when it is built, so the override is read there instead, and covers the HUD, the
 * world and every screen from one place. The override is cleared when the test asks for the scale
 * the game itself would compute, so a restored test leaves the client exactly as it found it, with
 * the game's own computation deciding again.
 */
public final class GrugGuiScale {

    /** The scale a test forced, or null while the game's own computation decides. */
    private static Integer forced;

    private GrugGuiScale() {}

    /**
     * Forces {@code scale} for every window built from now on, or clears the override when it is
     * the scale the game itself computes for a framebuffer of {@code width} by {@code height}.
     */
    public static void setForced(int scale, int width, int height) {
        forced = scale == natural(width, height) ? null : Integer.valueOf(scale);
    }

    /** The forced scale, or null while the game's own computation decides. */
    public static Integer forced() {
        return forced;
    }

    /**
     * The scale this version's own window computes for a framebuffer, which is the game's rule and
     * not a preference: the largest factor whose size still holds 320 by 240, and never below 1.
     * The window's loop says the same thing, since a size divided by a factor still holds a
     * dimension exactly when the size holds that dimension times the factor.
     */
    public static int natural(int width, int height) {
        return Math.max(1, Math.min(width / 320, height / 240));
    }
}
