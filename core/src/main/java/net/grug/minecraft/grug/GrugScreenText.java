package net.grug.minecraft.grug;

/**
 * One text an overlay's render() recorded for the loader to draw on the screen, in the game's own
 * font with its shadow, at a screen pixel.
 */
public final class GrugScreenText {
    private final String text;
    private final double x;
    private final double y;
    private final int rgb;

    /**
     * @param text what to draw
     * @param x the screen pixel of the text's top-left corner, from the window's top-left
     * @param y the screen pixel of the text's top-left corner, from the window's top-left
     * @param rgb the colour, without an alpha
     */
    public GrugScreenText(String text, double x, double y, int rgb) {
        this.text = text;
        this.x = x;
        this.y = y;
        this.rgb = rgb;
    }

    /** What to draw. */
    public String text() {
        return text;
    }

    /** The screen pixel of the text's left edge, from the window's left. */
    public double x() {
        return x;
    }

    /** The screen pixel of the text's top edge, from the window's top. */
    public double y() {
        return y;
    }

    /** The colour, without an alpha. */
    public int rgb() {
        return rgb;
    }
}
