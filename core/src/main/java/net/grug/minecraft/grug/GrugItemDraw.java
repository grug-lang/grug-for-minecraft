package net.grug.minecraft.grug;

import java.util.Random;

/**
 * One stack the draw_item host function recorded during a block entity's frame pass.
 *
 * <p>A frame pass is the per-frame half of a block entity's rendering: what {@code render()}
 * records is baked into the chunk's geometry, which does not change with the camera or between
 * ticks, so anything that turns to face the player or moves every frame is drawn from {@code
 * render_frame()} instead. The draw is a position relative to the block, the stack, and nothing
 * else: the loader decides what the item looks like, because that is the game's own dropped-item
 * drawing.
 *
 * <p>The copy count and the scatter of the extra copies are the reference's own numbers, so they
 * live here rather than in each loader: BuildCraft 2.2.14's {@code RenderPipe.doRenderItem} draws a
 * stack of more than 1 as 2 copies, more than 5 as 3 and more than 20 as 4, and scatters each extra
 * copy by values from a {@link Random} re-seeded with 187 for every stack. Keeping the sequence
 * here means every loader scatters a stack the same way, and a Java test pins it.
 */
public final class GrugItemDraw implements GrugFrameDraw {
    /** The seed BuildCraft re-seeds its scatter random with for every item. */
    private static final long SCATTER_SEED = 187L;

    private final Object item;
    private final int damage;
    private final int count;
    private final double x;
    private final double y;
    private final double z;

    /**
     * @param item the game's item object, which every loader casts to its own item class
     * @param damage the stack's damage, which is the block's metadata on versions that have one
     * @param count the whole stack, which decides how many copies are drawn
     * @param x the position to draw at, in blocks from the block's minimum corner
     */
    public GrugItemDraw(Object item, int damage, int count, double x, double y, double z) {
        this.item = item;
        this.damage = damage;
        this.count = count;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    /** The game's item object, which a loader casts to its own item class. */
    public Object item() {
        return item;
    }

    /** The stack's damage, which is the block's metadata on the versions that have one. */
    public int damage() {
        return damage;
    }

    /** The whole stack, which decides how many copies are drawn. */
    public int count() {
        return count;
    }

    /** The position to draw at, in blocks from the block's minimum corner. */
    public double x() {
        return x;
    }

    /** The position to draw at, in blocks from the block's minimum corner. */
    public double y() {
        return y;
    }

    /** The position to draw at, in blocks from the block's minimum corner. */
    public double z() {
        return z;
    }

    /**
     * How many copies the stack is drawn as, or 0 for a count of 0 or less, which draws nothing.
     *
     * <p>The thresholds are BuildCraft's {@code byte0}: more than 1 is 2 copies, more than 5 is 3,
     * more than 20 is 4, and anything else is one.
     */
    public int copies() {
        if (count <= 0) {
            return 0;
        }
        if (count > 20) {
            return 4;
        }
        if (count > 5) {
            return 3;
        }
        if (count > 1) {
            return 2;
        }
        return 1;
    }

    /**
     * Why a draw cannot be made with these numbers, or null when it can.
     *
     * <p>A damage and a count are counted in whole things, and a position that is not finite has no
     * place on screen, so each is refused rather than rounded or drawn nowhere. Kept here rather
     * than in the host function so a Java test can reach every refusal, which a host function's
     * report cannot: that goes through the JNI layer and needs a live grug state.
     */
    public static String problem(double damage, double count, double x, double y, double z) {
        String problem = wholeNumber("damage", damage);
        if (problem == null) {
            problem = wholeNumber("count", count);
        }
        if (problem == null) {
            problem = finite("x", x);
        }
        if (problem == null) {
            problem = finite("y", y);
        }
        if (problem == null) {
            problem = finite("z", z);
        }
        return problem;
    }

    private static String wholeNumber(String name, double value) {
        if (value < 0 || value > Integer.MAX_VALUE || value != Math.floor(value)) {
            return "The "
                    + name
                    + " has to be a whole number from 0 to "
                    + Integer.MAX_VALUE
                    + ", and was "
                    + value
                    + ".";
        }
        return null;
    }

    private static String finite(String name, double value) {
        if (!Double.isFinite(value)) {
            return "The " + name + " has to be finite, and was " + value + ".";
        }
        return null;
    }

    /**
     * The scatter of one copy, in blocks before the loader scales it, as x, y and z each between -1
     * and 1.
     *
     * <p>Copy 0 is never scattered and answers zeroes. Every later copy takes the next three floats
     * of a random re-seeded with 187, exactly as the reference does, so the copies land in the same
     * places for every stack and every loader. The loader multiplies by the reference's own factor:
     * 0.2 divided by the cube's scale for a block, and 0.3 for a flat sprite.
     */
    public static double[] scatter(int copy) {
        double[] scatter = {0.0, 0.0, 0.0};
        if (copy <= 0) {
            return scatter;
        }

        Random random = new Random(SCATTER_SEED);
        // The three floats of every copy before this one, so this copy gets the same values as in
        // the reference's one long run of nextFloat calls.
        for (int i = 0; i < (copy - 1) * 3; i++) {
            random.nextFloat();
        }
        scatter[0] = random.nextFloat() * 2.0 - 1.0;
        scatter[1] = random.nextFloat() * 2.0 - 1.0;
        scatter[2] = random.nextFloat() * 2.0 - 1.0;
        return scatter;
    }
}
