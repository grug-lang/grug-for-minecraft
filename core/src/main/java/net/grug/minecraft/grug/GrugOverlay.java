package net.grug.minecraft.grug;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The one overlay pass being asked right now, and what its script recorded while it was: the item
 * icons and highlight boxes a render() drew, and whether a click() took the press.
 *
 * <p>A pass belongs to the loader, not to a script: the loader opens one before it asks an
 * overlay's export, closes it afterwards, and acts on what was recorded. That is the only place a
 * draw or a take can go, because the icon has to land on the screen the loader's {@code
 * GuiGraphics} is drawing and the take has to cancel the loader's own click event, and only the
 * loader knows what either is.
 *
 * <p>There are two kinds of pass. A render pass is opened for {@code render()} and records the
 * icons and highlight boxes the loader draws on the screen for that frame. A click pass is opened
 * for {@code click()} and carries the press the overlay may take. The two kinds cannot be mixed: a
 * render pass refuses {@code take()}, and a click pass refuses a draw, because neither has anywhere
 * to go.
 *
 * <p>There is at most one open pass at a time. The caller (the loader's event handler) holds {@link
 * GrugStateLock} across the whole pass, for the same reason as the block entity's render pass: the
 * pass's bookkeeping is static state and two concurrent passes would cross one overlay's draws into
 * another's frame.
 *
 * <p>Nothing here reports an error itself. A context method called outside its pass is reported by
 * the caller that can, {@link HostFunctions}, the way a render context out of its pass is.
 */
public final class GrugOverlay {
    /**
     * The context a pass hands its export, which is what the context's methods check before they
     * act.
     *
     * <p>A new one is made for every pass and cleared when the pass closes, so a script that saves
     * the context in a member variable and uses it in a later call is refused: the context is no
     * longer the one the open pass was given.
     */
    public static final class Context {
        @GrugGenerated("marker: only an overlay pass makes one")
        private Context() {}
    }

    private static final List<GrugScreenItemDraw> ITEMS = new ArrayList<>();
    private static final List<GrugScreenBox> BOXES = new ArrayList<>();

    private static boolean open = false;
    private static boolean render = false;
    private static boolean taken = false;
    private static Context context = null;

    // The screen the open pass is being asked about, in pixels from the window's top-left.
    private static double screenWidth = 0.0;
    private static double screenHeight = 0.0;
    private static double guiLeft = 0.0;
    private static double guiTop = 0.0;
    private static double guiWidth = 0.0;
    private static double guiHeight = 0.0;
    private static double mouseX = 0.0;
    private static double mouseY = 0.0;
    private static double button = 0.0;

    /**
     * Whether this loader has ever asked an overlay's export on a screen it may draw on, which is
     * what {@code Test.overlay_asked} answers. A loader that does not draw overlays never opens a
     * pass, so it keeps answering false, and a test that needs the overlay to have drawn its one
     * job is the one that checks it.
     */
    private static boolean overlayAsked = false;

    @GrugGenerated("utility class: never instantiated")
    private GrugOverlay() {}

    /**
     * Marks that this loader asked an overlay, so Test.overlay_asked can answer true from then on.
     */
    public static void noteAsked() {
        overlayAsked = true;
    }

    /** Whether an overlay's exports were ever asked in this run. */
    public static boolean overlayAsked() {
        return overlayAsked;
    }

    /** Whether a pass is open, which is the only state a context method is legal in. */
    public static boolean isOpen() {
        return open;
    }

    /** Whether the open pass is a render pass, which is the only state a draw is legal in. */
    public static boolean isRender() {
        return open && render;
    }

    /** Whether the open pass is a click pass, which is the only state a take is legal in. */
    public static boolean isClick() {
        return open && !render;
    }

    /**
     * The context the open pass was given, which a loader hands to the pass's export, or null when
     * no pass is open.
     */
    public static Context context() {
        return context;
    }

    // The screen the open pass is being asked about.

    public static double screenWidth() {
        return screenWidth;
    }

    public static double screenHeight() {
        return screenHeight;
    }

    public static double guiLeft() {
        return guiLeft;
    }

    public static double guiTop() {
        return guiTop;
    }

    public static double guiWidth() {
        return guiWidth;
    }

    public static double guiHeight() {
        return guiHeight;
    }

    public static double mouseX() {
        return mouseX;
    }

    public static double mouseY() {
        return mouseY;
    }

    public static double button() {
        return button;
    }

    /**
     * Opens a render pass for one frame, with nothing recorded in it, for the screen the geometry
     * describes.
     */
    public static void openRender(
            double screenWidth,
            double screenHeight,
            double guiLeft,
            double guiTop,
            double guiWidth,
            double guiHeight,
            double mouseX,
            double mouseY) {
        open(
                false,
                screenWidth,
                screenHeight,
                guiLeft,
                guiTop,
                guiWidth,
                guiHeight,
                mouseX,
                mouseY,
                0.0);
    }

    /**
     * Opens a click pass for one press, with nothing recorded in it, for the screen the geometry
     * describes and the button that was pressed.
     */
    public static void openClick(
            double screenWidth,
            double screenHeight,
            double guiLeft,
            double guiTop,
            double guiWidth,
            double guiHeight,
            double mouseX,
            double mouseY,
            double button) {
        open(
                true,
                screenWidth,
                screenHeight,
                guiLeft,
                guiTop,
                guiWidth,
                guiHeight,
                mouseX,
                mouseY,
                button);
    }

    private static void open(
            boolean isClick,
            double screenWidth,
            double screenHeight,
            double guiLeft,
            double guiTop,
            double guiWidth,
            double guiHeight,
            double mouseX,
            double mouseY,
            double button) {
        if (open) {
            throw Grug.fatal("An overlay pass is already open.");
        }
        ITEMS.clear();
        BOXES.clear();
        render = !isClick;
        taken = false;
        context = new Context();
        GrugOverlay.screenWidth = screenWidth;
        GrugOverlay.screenHeight = screenHeight;
        GrugOverlay.guiLeft = guiLeft;
        GrugOverlay.guiTop = guiTop;
        GrugOverlay.guiWidth = guiWidth;
        GrugOverlay.guiHeight = guiHeight;
        GrugOverlay.mouseX = mouseX;
        GrugOverlay.mouseY = mouseY;
        GrugOverlay.button = button;
        open = true;
    }

    /**
     * Records one item icon for the render pass to draw.
     *
     * @return false when no render pass is open. The caller reports that rather than this class, so
     *     the message names the call and where it is legal.
     */
    public static boolean recordItem(Object item, double x, double y) {
        if (!isRender()) {
            return false;
        }
        ITEMS.add(new GrugScreenItemDraw(item, x, y));
        return true;
    }

    /**
     * Records one highlight box for the render pass to draw.
     *
     * <p>A box whose far corner is nearer than its near corner is refused rather than drawn, the
     * way a block's box is: the author asked for a rectangle and gets one, and the two corners that
     * define it have to be the right way round.
     *
     * @return false when no render pass is open, or when the box is the wrong way round. The caller
     *     reports that rather than this class, and nothing is recorded either way.
     */
    public static boolean recordBox(double x1, double y1, double x2, double y2) {
        if (!isRender()) {
            return false;
        }
        if (x1 > x2 || y1 > y2) {
            return false;
        }
        BOXES.add(new GrugScreenBox(x1, y1, x2, y2));
        return true;
    }

    /** Called by the {@code OverlayClickContext.take} host function. */
    public static void take() {
        if (isClick()) {
            taken = true;
        }
    }

    /**
     * Closes a render pass and returns what the render function recorded, in the order it drew it.
     *
     * <p>Copies, so the geometry a loader is about to draw cannot be changed by the next pass, and
     * so that closing the pass does not hand out the live lists.
     */
    public static GrugOverlayGeometry closeRender() {
        if (!render) {
            throw Grug.fatal(
                    "An overlay pass was closed as a render pass while it was a click pass.");
        }
        GrugOverlayGeometry geometry =
                new GrugOverlayGeometry(new ArrayList<>(ITEMS), new ArrayList<>(BOXES));
        finish();
        return geometry;
    }

    /**
     * Closes a click pass and answers whether the press was taken, so the loader can keep it from
     * reaching the screen.
     */
    public static boolean closeClick() {
        if (render) {
            throw Grug.fatal(
                    "An overlay pass was closed as a click pass while it was a render pass.");
        }
        boolean wasTaken = taken;
        finish();
        return wasTaken;
    }

    private static void finish() {
        ITEMS.clear();
        BOXES.clear();
        open = false;
        render = false;
        taken = false;
        context = null;
    }

    /** The item icons recorded so far, in the order they were drawn. */
    public static List<GrugScreenItemDraw> recordedItems() {
        return Collections.unmodifiableList(new ArrayList<>(ITEMS));
    }
}
