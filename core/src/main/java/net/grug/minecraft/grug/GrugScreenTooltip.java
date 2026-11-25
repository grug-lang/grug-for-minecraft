package net.grug.minecraft.grug;

/**
 * One tooltip an overlay's render() recorded for the loader to draw on the screen: the game's own
 * tooltip, its panel and its text, with the text's line at the point the script gave.
 *
 * <p>The panel is the loader's to draw, because every version draws a tooltip its own way and the
 * reference's own tooltip is the game's. The point is the screen pixel the tooltip is anchored at,
 * which is where the reference passes the mouse: each loader places the text and its panel from
 * there by its version's own rule.
 */
public final class GrugScreenTooltip {
    private final String text;
    private final double x;
    private final double y;

    /**
     * @param text the line to draw
     * @param x the anchor's screen pixel, from the window's top-left
     * @param y the anchor's screen pixel, from the window's top-left
     */
    public GrugScreenTooltip(String text, double x, double y) {
        this.text = text;
        this.x = x;
        this.y = y;
    }

    /** The line to draw. */
    public String text() {
        return text;
    }

    /** The anchor's screen pixel, from the window's top-left. */
    public double x() {
        return x;
    }

    /** The anchor's screen pixel, from the window's top-left. */
    public double y() {
        return y;
    }
}
