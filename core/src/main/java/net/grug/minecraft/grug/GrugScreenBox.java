package net.grug.minecraft.grug;

/**
 * One box an overlay's render() recorded for the loader to draw on the screen: the overlay's hover
 * highlight, a translucent white rectangle marking the slot the mouse is over.
 *
 * <p>The highlight is the reference's own drawing for a hovered slot, so its colour is fixed here
 * rather than passed by the script: the overlay marks the slot under the mouse and nothing else.
 * The corners are screen pixels from the window's top-left, the first of each pair no greater than
 * the second, like a box.
 */
public final class GrugScreenBox {
    private final double x1;
    private final double y1;
    private final double x2;
    private final double y2;

    /**
     * @param x1 the nearer corner's screen pixel, from the window's top-left
     * @param y1 the nearer corner's screen pixel, from the window's top-left
     * @param x2 the far corner's screen pixel, from the window's top-left
     * @param y2 the far corner's screen pixel, from the window's top-left
     */
    public GrugScreenBox(double x1, double y1, double x2, double y2) {
        this.x1 = x1;
        this.y1 = y1;
        this.x2 = x2;
        this.y2 = y2;
    }

    /** The nearer corner's screen pixel, from the window's top-left. */
    public double x1() {
        return x1;
    }

    /** The nearer corner's screen pixel, from the window's top-left. */
    public double y1() {
        return y1;
    }

    /** The far corner's screen pixel, from the window's top-left. */
    public double x2() {
        return x2;
    }

    /** The far corner's screen pixel, from the window's top-left. */
    public double y2() {
        return y2;
    }
}
