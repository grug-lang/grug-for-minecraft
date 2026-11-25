package net.grug.minecraft.grug;

/**
 * A liquid drawn inside a box for one frame, as the {@code draw_liquid} host function recorded it.
 *
 * <p>The tank's column and a waterproof pipe's contents are a game liquid's own still texture on a
 * box, so a mod ships no texture for them: the loader looks the liquid up in its own tables and
 * draws the sprite the game gives it. What core records is the box and the canonical liquid name,
 * which is all a loader needs and all a test can check without a renderer.
 *
 * <p>The box is reached from the block's minimum corner, exactly as {@link GrugBox} is, so a loader
 * adds the block's position to the corners. Drawing is in the order the script called it, with the
 * other frame draws.
 */
public final class GrugLiquidDraw implements GrugFrameDraw {
    private final String liquid;
    private final double x1;
    private final double y1;
    private final double z1;
    private final double x2;
    private final double y2;
    private final double z2;

    /**
     * @param liquid the still liquid's canonical name, such as {@code minecraft:water}
     */
    public GrugLiquidDraw(
            String liquid, double x1, double y1, double z1, double x2, double y2, double z2) {
        this.liquid = liquid;
        this.x1 = x1;
        this.y1 = y1;
        this.z1 = z1;
        this.x2 = x2;
        this.y2 = y2;
        this.z2 = z2;
    }

    /** The still liquid's canonical name, such as {@code minecraft:water}. */
    public String liquid() {
        return liquid;
    }

    public double x1() {
        return x1;
    }

    public double y1() {
        return y1;
    }

    public double z1() {
        return z1;
    }

    public double x2() {
        return x2;
    }

    public double y2() {
        return y2;
    }

    public double z2() {
        return z2;
    }

    @Override
    public String toString() {
        return "GrugLiquidDraw[" + liquid + "]";
    }
}
