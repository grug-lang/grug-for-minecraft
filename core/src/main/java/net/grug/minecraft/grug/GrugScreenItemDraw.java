package net.grug.minecraft.grug;

/**
 * One item icon an overlay's render() recorded for the loader to draw on the screen, at a screen
 * pixel.
 *
 * <p>The icon is the item's own 16 by 16 inventory icon, drawn the way the game draws it in an
 * inventory slot: a block as its inventory shape, anything else as its flat icon, unlit and full
 * brightness. What the icon looks like is the loader's, because it is the game's own inventory
 * drawing; what is decided here is only where it goes.
 */
public final class GrugScreenItemDraw {
    private final Object item;
    private final double x;
    private final double y;

    /**
     * @param item the game's item object, which the loader casts to its own item class
     * @param x the screen pixel of the icon's top-left corner, from the window's top-left
     * @param y the screen pixel of the icon's top-left corner, from the window's top-left
     */
    public GrugScreenItemDraw(Object item, double x, double y) {
        this.item = item;
        this.x = x;
        this.y = y;
    }

    /** The game's item object, which a loader casts to its own item class. */
    public Object item() {
        return item;
    }

    /** The screen pixel of the icon's top-left corner, from the window's top-left. */
    public double x() {
        return x;
    }

    /** The screen pixel of the icon's top-left corner, from the window's top-left. */
    public double y() {
        return y;
    }
}
