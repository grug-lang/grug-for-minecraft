package net.grug.minecraft.grug;

import java.util.ArrayList;
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
    private static boolean keyPass = false;
    private static boolean scrollPass = false;
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
    private static boolean shift = false;
    private static double keyCode = 0.0;
    private static boolean ctrl = false;
    private static double scrollDelta = 0.0;

    /**
     * Whether this loader draws overlays at all, which is what {@code Test.overlay_supported}
     * answers. Only a loader that registers the overlay hooks says so, and it says so from load,
     * before any screen has been open, so a test can ask before it opens one. A loader that does
     * not draw overlays keeps answering false.
     */
    private static volatile boolean overlaySupported = false;

    /**
     * Whether a frame has drawn the running test's own overlay on the screen that is open now,
     * which is what {@code Test.overlay_drawn} answers. A loader sets it when it draws an overlay
     * belonging to the running test's mod on an eligible screen, and clears it when an eligible
     * screen opens, so a test waits for a frame of its own screen rather than reading an earlier
     * test's mark. The frame really was rendered rather than one the test ran past at the game's
     * maximum speed.
     */
    private static volatile boolean overlayDrawn = false;

    /**
     * Whether an overlay took the press {@code Test.click_screen} is driving. The loader's click
     * handler records it, and the adapter clears it before it delivers a press, so the test answers
     * with the overlay's own decision rather than with whether the screen handled the press too.
     */
    private static volatile boolean testPressTaken = false;

    @GrugGenerated("utility class: never instantiated")
    private GrugOverlay() {}

    /**
     * Marks that this loader draws overlays, so Test.overlay_supported can answer true from then
     * on. Called by a loader when it registers the screen hooks, not when it first asks an export,
     * since a test needs the answer before it opens the screen that would ask one.
     */
    public static void noteSupported() {
        overlaySupported = true;
    }

    /**
     * The number an overlay's {@code key()} is given for a key whose LWJGL 2 name is {@code
     * keyName}, or -1 when the key is not asked: the upper case character code of a letter or a
     * digit key, which is the same number on every loader. A name longer than one character (an
     * arrow, a function key, a numpad key) and a name that is not a letter or a digit are not
     * asked.
     */
    public static int keyCodeForName(String keyName) {
        if (keyName == null || keyName.length() != 1) return -1;
        char c = Character.toUpperCase(keyName.charAt(0));
        boolean letter = c >= 'A' && c <= 'Z';
        boolean digit = c >= '0' && c <= '9';
        return letter || digit ? c : -1;
    }

    /** Whether this loader draws overlays on the inventory and container screens. */
    public static boolean overlaySupported() {
        return overlaySupported;
    }

    /**
     * Marks that a frame drew the running test's overlay, so Test.overlay_drawn can answer true
     * from then on. Called by a loader after it drew an overlay belonging to the running test's mod
     * on an eligible screen, on the render thread.
     */
    public static void noteDrawn() {
        overlayDrawn = true;
    }

    /**
     * Clears the drawn mark when an eligible screen opens, so Test.overlay_drawn speaks about the
     * screen that is open now rather than about a frame an earlier test drew on an earlier screen.
     * Called by a loader when an eligible screen initializes, on the client thread.
     */
    public static void screenOpened() {
        overlayDrawn = false;
    }

    /** Whether a frame drew the running test's overlay on the screen that is open now. */
    public static boolean overlayDrawn() {
        return overlayDrawn;
    }

    /** Clears the press record, so the press the adapter is about to deliver starts untaken. */
    public static void beginTestPress() {
        testPressTaken = false;
    }

    /** Records that an overlay took the press the adapter is delivering. */
    public static void noteTestPressTaken() {
        testPressTaken = true;
    }

    /** Whether an overlay took the press Test.click_screen is driving. */
    public static boolean testPressTaken() {
        return testPressTaken;
    }

    /** Whether a pass is open, which is the only state a context method is legal in. */
    public static boolean isOpen() {
        return open;
    }

    /** Whether the open pass is a render pass, which is the only state a draw is legal in. */
    public static boolean isRender() {
        return open && render;
    }

    /**
     * Whether the open pass is a click pass, which is the only state a click's take is legal in.
     */
    public static boolean isClick() {
        return open && !render && !keyPass && !scrollPass;
    }

    /** Whether the open pass is a key pass, which is the only state a key's take is legal in. */
    public static boolean isKey() {
        return open && keyPass;
    }

    /**
     * Whether the open pass is a scroll pass, which is the only state a scroll's methods are legal
     * in.
     */
    public static boolean isScroll() {
        return open && scrollPass;
    }

    /**
     * The wheel movement the open scroll pass was given, see {@code OverlayScrollContext.delta}.
     */
    public static double scrollDelta() {
        return scrollDelta;
    }

    /** The key the open key pass was given, see {@code OverlayKeyContext.key}. */
    public static double keyCode() {
        return keyCode;
    }

    /** Whether control was held for the open key pass's key. */
    public static boolean ctrl() {
        return ctrl;
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

    /** Whether shift was held for the press, which is half of the reference's stack binding. */
    public static boolean shift() {
        return shift;
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
                0.0,
                false);
    }

    /**
     * Opens a click pass for one press, with nothing recorded in it, for the screen the geometry
     * describes, the button that was pressed and whether shift was held.
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
            double button,
            boolean shift) {
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
                button,
                shift);
    }

    /**
     * Opens a key pass for one key press, with nothing recorded in it, for the screen the geometry
     * describes. The key is the printable key's upper case character code, or the platform's own
     * code for a key with no character, and {@code ctrl} says whether control was held.
     */
    public static void openKey(double keyCode, boolean ctrl) {
        open(true, 0, 0, 0, 0, 0, 0, 0, 0, 0.0, false);
        keyPass = true;
        GrugOverlay.keyCode = keyCode;
        GrugOverlay.ctrl = ctrl;
    }

    /**
     * Opens a scroll pass for one wheel movement at a mouse position, with nothing recorded in it,
     * for the screen the geometry describes. The delta is in wheel notches, positive when the wheel
     * was turned up and away from the player.
     */
    public static void openScroll(
            double screenWidth,
            double screenHeight,
            double guiLeft,
            double guiTop,
            double guiWidth,
            double guiHeight,
            double mouseX,
            double mouseY,
            double delta) {
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
                0.0,
                false);
        scrollPass = true;
        scrollDelta = delta;
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
            double button,
            boolean shift) {
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
        GrugOverlay.shift = shift;
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

    /** Called by the {@code take} functions of the click, key and scroll contexts. */
    public static void take() {
        if (open && !render) {
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
                    "An overlay pass was closed as a render pass while it was not a render pass.");
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
        if (render || keyPass || scrollPass) {
            throw Grug.fatal(
                    "An overlay pass was closed as a click pass while it was not a click pass.");
        }
        boolean wasTaken = taken;
        finish();
        return wasTaken;
    }

    /**
     * Closes a key pass and answers whether the key was taken, so the loader can keep it from
     * reaching the screen.
     */
    public static boolean closeKey() {
        if (!keyPass) {
            throw Grug.fatal(
                    "An overlay pass was closed as a key pass while it was not a key pass.");
        }
        boolean wasTaken = taken;
        finish();
        return wasTaken;
    }

    /**
     * Closes a scroll pass and answers whether the scroll was taken, so the loader can keep it from
     * reaching the screen.
     */
    public static boolean closeScroll() {
        if (!scrollPass) {
            throw Grug.fatal(
                    "An overlay pass was closed as a scroll pass while it was not a scroll pass.");
        }
        boolean wasTaken = taken;
        finish();
        return wasTaken;
    }

    private static void finish() {
        scrollPass = false;
        scrollDelta = 0.0;
        keyPass = false;
        keyCode = 0.0;
        ctrl = false;
        ITEMS.clear();
        BOXES.clear();
        open = false;
        render = false;
        taken = false;
        shift = false;
        context = null;
    }

    /**
     * Runs an overlay's {@code render()} for one frame and answers what it drew.
     *
     * <p>The loader calls this from the point in its frame where the screen is already drawn and an
     * item icon can still be put on top of it, and draws the answer straight away. The pass belongs
     * here rather than in the loader because the bookkeeping is static and the export needs a
     * context entity, which is core's; what the loader owns is the drawing and the event that says
     * when to draw.
     *
     * <p>The pass runs under the state lock, like a block entity's frame pass: two concurrent
     * passes would interleave the static bookkeeping and draw one overlay's icons into another
     * frame. The lock is reentrant, so the export's own host functions take it again on this thread
     * without deadlocking.
     *
     * <p>A handle of zero means the mod has no overlay script, and an invalid export id means the
     * engine has no {@code render} to call, so neither is passed to the native side. The pass still
     * opens and closes, because {@link #noteSupported} has already said this loader draws overlays.
     */
    public static GrugOverlayGeometry render(
            long entityHandle,
            long renderFnId,
            double screenWidth,
            double screenHeight,
            double guiLeft,
            double guiTop,
            double guiWidth,
            double guiHeight,
            double mouseX,
            double mouseY) {
        GrugStateLock.lock();
        try {
            openRender(
                    screenWidth,
                    screenHeight,
                    guiLeft,
                    guiTop,
                    guiWidth,
                    guiHeight,
                    mouseX,
                    mouseY);
            GrugOverlayGeometry geometry;
            try {
                // The pass closes even when the export does not come back, because callExportFn
                // rethrows a fatal raised inside the script once the native call returns to Java. A
                // pass left open would make the next frame's open() hit the nesting invariant
                // instead of naming the defect that actually happened.
                if (entityHandle != 0 && renderFnId != Grug.INVALID_GRUG_EXPORT_FN_ID) {
                    callExport(
                            entityHandle, renderFnId, GrugEntityType.OverlayRenderContext, context);
                }
            } finally {
                geometry = closeRender();
            }
            return geometry;
        } finally {
            GrugStateLock.unlock();
        }
    }

    /**
     * Runs an overlay's {@code click()} for one press and answers whether it took the press.
     *
     * <p>The loader calls this before its own screen sees the press, and cancels the press when the
     * answer is true, so a click on the overlay's list does not also click the vanilla slot under
     * it. The pass has the same lock and the same report-free close as {@link #render}.
     */
    public static boolean click(
            long entityHandle,
            long clickFnId,
            double screenWidth,
            double screenHeight,
            double guiLeft,
            double guiTop,
            double guiWidth,
            double guiHeight,
            double mouseX,
            double mouseY,
            double button,
            boolean shift) {
        GrugStateLock.lock();
        try {
            openClick(
                    screenWidth,
                    screenHeight,
                    guiLeft,
                    guiTop,
                    guiWidth,
                    guiHeight,
                    mouseX,
                    mouseY,
                    button,
                    shift);
            boolean wasTaken;
            try {
                if (entityHandle != 0 && clickFnId != Grug.INVALID_GRUG_EXPORT_FN_ID) {
                    callExport(
                            entityHandle, clickFnId, GrugEntityType.OverlayClickContext, context);
                }
            } finally {
                wasTaken = closeClick();
            }
            return wasTaken;
        } finally {
            GrugStateLock.unlock();
        }
    }

    /**
     * Runs an overlay's {@code key()} for one key press and answers whether it took the key. The
     * loader calls this before its screen sees the key and cancels the key when the answer is true.
     */
    public static boolean key(long entityHandle, long keyFnId, double keyCode, boolean ctrl) {
        GrugStateLock.lock();
        try {
            openKey(keyCode, ctrl);
            boolean wasTaken;
            try {
                if (entityHandle != 0 && keyFnId != Grug.INVALID_GRUG_EXPORT_FN_ID) {
                    callExport(entityHandle, keyFnId, GrugEntityType.OverlayKeyContext, context);
                }
            } finally {
                wasTaken = closeKey();
            }
            return wasTaken;
        } finally {
            GrugStateLock.unlock();
        }
    }

    /**
     * Runs an overlay's {@code scroll()} for one wheel movement and answers whether it took it. The
     * loader calls this before its screen sees the wheel and cancels it when the answer is true.
     */
    public static boolean scroll(
            long entityHandle,
            long scrollFnId,
            double screenWidth,
            double screenHeight,
            double guiLeft,
            double guiTop,
            double guiWidth,
            double guiHeight,
            double mouseX,
            double mouseY,
            double delta) {
        GrugStateLock.lock();
        try {
            openScroll(
                    screenWidth,
                    screenHeight,
                    guiLeft,
                    guiTop,
                    guiWidth,
                    guiHeight,
                    mouseX,
                    mouseY,
                    delta);
            boolean wasTaken;
            try {
                if (entityHandle != 0 && scrollFnId != Grug.INVALID_GRUG_EXPORT_FN_ID) {
                    callExport(
                            entityHandle, scrollFnId, GrugEntityType.OverlayScrollContext, context);
                }
            } finally {
                wasTaken = closeScroll();
            }
            return wasTaken;
        } finally {
            GrugStateLock.unlock();
        }
    }

    /**
     * Calls an overlay export with the pass's context, rooting its host entities in this call only.
     *
     * <p>A render export runs every frame, so a call such as {@code item_at} inside it would
     * otherwise add one entity per frame to the process-wide root list and keep them alive. The
     * context entity exists only for the call, so a script that saves it and uses it later finds a
     * stale context and is refused.
     */
    @GrugGenerated(
            "the call into the VM and its context entity: needs a live grug entity, which only a"
                    + " game has")
    private static void callExport(
            long entityHandle, long exportFnId, GrugEntityType contextType, Context context) {
        GrugEntities.withFreshOwner(
                () -> {
                    long contextHandle = GrugEntities.addEntity(contextType, context);
                    try {
                        Grug.callExportFn(entityHandle, exportFnId, contextHandle);
                    } finally {
                        GrugEntities.removeEntity(contextHandle);
                    }
                });
    }
}
