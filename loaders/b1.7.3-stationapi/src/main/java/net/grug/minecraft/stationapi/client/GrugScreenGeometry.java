package net.grug.minecraft.stationapi.client;

/**
 * The geometry a container screen's overlay is asked about, on the screens this loader mixes into.
 *
 * <p>Beta's {@code HandledScreen} keeps its GUI size protected and works its left and top out from
 * the screen size, so the mixin answers those numbers here and the host and the test read them
 * through one shape rather than each guessing at a window size.
 */
public interface GrugScreenGeometry {

    /** The screen's width in scaled pixels. */
    int grug$width();

    /** The screen's height in scaled pixels. */
    int grug$height();

    /** The x the screen's GUI is drawn from. */
    int grug$guiLeft();

    /** The y the screen's GUI is drawn from. */
    int grug$guiTop();

    /** The width of the screen's GUI. */
    int grug$guiWidth();

    /** The height of the screen's GUI. */
    int grug$guiHeight();

    /**
     * Delivers one press through the screen's own click path and answers whether an overlay took
     * it. The mixin implements this so a test can reach the hook that cancels the press instead of
     * calling the host directly.
     */
    boolean grug$click(int mouseX, int mouseY, int button);

    /**
     * Delivers one press of the key with LWJGL code {@code key} through the screen's own key path
     * and answers whether an overlay took it. The mixin implements this so a test can reach the
     * hook that cancels the key instead of calling the host directly.
     */
    boolean grug$pressKey(int key);

    /**
     * Runs the screen's own mouse handler for the wheel movement the host is holding for a test,
     * and answers whether an overlay took it. The mixin implements this so a test can reach the
     * hook that cancels the movement instead of calling the host directly.
     */
    boolean grug$scroll();
}
