package net.grug.minecraft.grug;

/**
 * The context a screen's {@code screen_init} is passed, telling the mod the size it lays its
 * widgets out in.
 *
 * <p>A context exists only while its call runs. {@link GrugOwnedScreens} makes a new one for every
 * call and clears it when the call returns, so a context saved in a member variable and used later
 * is refused.
 */
public final class GrugScreenInitContext {
    private final GrugOwnedScreen screen;
    private final double width;
    private final double height;

    GrugScreenInitContext(GrugOwnedScreen screen, double width, double height) {
        this.screen = screen;
        this.width = width;
        this.height = height;
    }

    /** The screen this call is laying out. */
    public GrugOwnedScreen screen() {
        return screen;
    }

    /** The width of the screen in GUI-scaled pixels. */
    public double width() {
        return width;
    }

    /** The height of the screen in GUI-scaled pixels. */
    public double height() {
        return height;
    }
}
