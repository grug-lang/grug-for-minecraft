package net.grug.minecraft.grug;

import net.grug.minecraft.core.GrugFrameRequest;
import net.grug.minecraft.core.GrugOverlayFirstFrame;
import net.grug.minecraft.core.GrugTestScreens;

import java.lang.ref.WeakReference;
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
    private static final List<GrugScreenText> TEXTS = new ArrayList<>();

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
    private static double character = 0.0;
    private static boolean ctrl = false;
    private static boolean repeat = false;
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
     * which is what {@code TestTickContext.overlay_drawn} answers. A loader sets it when it draws
     * an overlay belonging to the running test's mod on an eligible screen, and clears it when an
     * eligible screen opens, so a test waits for a frame of its own screen rather than reading an
     * earlier test's mark. The frame really was rendered rather than one the test ran past at the
     * game's maximum speed.
     */
    private static volatile boolean overlayDrawn = false;

    /**
     * The mark's state at the moment the first frame of the screen that is open now began, which is
     * how a joined client's answer proves the screen-open hook ran before that frame (#1391).
     */
    private static final GrugOverlayFirstFrame FIRST_FRAME = new GrugOverlayFirstFrame();

    /**
     * Whether an overlay took the press {@code TestTickContext.click_screen} is driving. The
     * loader's click handler records it, and the adapter clears it before it delivers a press, so
     * the test answers with the overlay's own decision rather than with whether the screen handled
     * the press too.
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
     * digit key, which is the same number on every loader, 32 for the space bar and 8 for
     * backspace, which are the ASCII codes. Any other name longer than one character (an arrow, a
     * function key, a numpad key) and a name that is not a letter or a digit are not asked.
     */
    public static int keyCodeForName(String keyName) {
        if (keyName == null) return -1;
        if (keyName.equals("SPACE")) return KEY_SPACE;
        if (keyName.equals("BACK")) return KEY_BACKSPACE;
        if (keyName.length() != 1) return -1;
        char c = Character.toUpperCase(keyName.charAt(0));
        boolean letter = c >= 'A' && c <= 'Z';
        boolean digit = c >= '0' && c <= '9';
        return letter || digit ? c : -1;
    }

    /**
     * The LWJGL 2 name of the key a key() number stands for, the inverse of {@link
     * #keyCodeForName}: what a test's press of that number looks up on the loaders that name keys.
     */
    public static String keyNameForCode(int code) {
        if (code == KEY_SPACE) return "SPACE";
        if (code == KEY_BACKSPACE) return "BACK";
        return String.valueOf((char) code);
    }

    /**
     * The typed character a key() call carries for a character a loader reports, or 0 for one an
     * overlay is not given: a printable character from 32 up, except 127, which is delete. Every
     * other value is a control character or the code of a key with no text of its own.
     *
     * <p>A UTF-16 surrogate (0xD800 to 0xDFFF) is not given either. It is half of a code point
     * above the Basic Multilingual Plane, and the game reports such a code point as two surrogate
     * events (1.20.6 splits it itself, LWJGL 2 delivers one). Neither half is the character the
     * player typed, and {@code String.from_character} refuses a surrogate, so such a character
     * cannot be typed through this call at all: the event is dropped rather than sending half of
     * it.
     */
    public static int characterCode(int reported) {
        if (reported < KEY_SPACE || reported == 127) return 0;
        if (reported >= 0xD800 && reported <= 0xDFFF) return 0;
        return reported;
    }

    /**
     * Whether an event that carries a printable character is a key chord, which a text field leaves
     * to the screen, rather than a character of its own: control is held and alt is not.
     *
     * <p>An AltGr press, which is how a German or French layout types an at sign, a bar, a tilde, a
     * backslash or a brace, reaches the game on Windows as control and alt together: Windows sends
     * a spurious left control down beside the right alt, which is what Ctrl+Alt means there, and
     * GLFW keeps those bits on the character event. Its printable character belongs to the keyboard
     * layout and is typed, so it must not be mistaken for a chord. A real control chord (Ctrl and a
     * letter) has control without alt, and on X11 or macOS AltGr sets no control bit at all, so the
     * character is typed there either way. See #1741.
     */
    public static boolean characterEventIsChord(boolean ctrl, boolean alt) {
        return ctrl && !alt;
    }

    /**
     * The character a test's press of the key() number {@code keyCode} types, or 0 for a key that
     * types nothing. It is what the game produces without shift: a letter in lower case, the digit
     * of a digit key, the space bar, and a printable number that names no key (punctuation) itself.
     *
     * <p>A number above the printable ASCII range is not a character a press types: on a loader
     * that takes the game's own key codes it is one of those instead (256 is Escape, which a test
     * presses through the same call), so it types nothing.
     *
     * <p>{@code TestTickContext.press_screen_key} cannot press shift, so this is the whole set a
     * test can type. A real press carries the game's own character instead, shift included.
     */
    public static int typedCharacterFor(int keyCode) {
        boolean letter = keyCode >= 'A' && keyCode <= 'Z';
        boolean digit = keyCode >= '0' && keyCode <= '9';
        if (letter) return keyCode + 32;
        if (digit || keyCode == KEY_SPACE) return keyCode;
        if (keyCode > 126) return 0;
        return characterCode(keyCode);
    }

    /** The code a key pass gives the space bar, the same on every loader. */
    public static final int KEY_SPACE = 32;

    /** The code a key pass gives backspace, the same on every loader. */
    public static final int KEY_BACKSPACE = 8;

    /** Whether this loader draws overlays on the inventory and container screens. */
    public static boolean overlaySupported() {
        return overlaySupported;
    }

    /**
     * Marks that a frame drew the running test's overlay, so TestTickContext.overlay_drawn can
     * answer true from then on. Called by a loader after it drew an overlay belonging to the
     * running test's mod on an eligible screen, on the render thread.
     */
    public static void noteDrawn() {
        overlayDrawn = true;
    }

    /**
     * Notes that a frame of {@code screen} is about to be drawn, so the first-frame record can
     * compare the drawn mark with the hook that was supposed to clear it before this frame. Called
     * by a loader at the start of the frame pass it draws {@code screen}'s overlay in, on the
     * render thread, once per frame rather than once per overlay.
     *
     * <p>It is the client's own record, taken at the moment the frame begins, because a joined
     * client's frames are what a server-run test cannot see: by the time the server reads the mark,
     * a frame of the new screen may already have set it. The screen is passed in because a screen
     * the hook never announced still starts a new first frame, and a hook that runs after that
     * frame cannot clean it. See {@link GrugOverlayFirstFrame}.
     */
    public static void noteFrame(Object screen) {
        FIRST_FRAME.frameStarted(screen, overlayDrawn);
        if (frameScreen.get() != screen) {
            frameScreen = new WeakReference<>(screen);
            screenSerial++;
        }
    }

    /** The screen the last frame was drawn on, held weakly so a closed screen can be collected. */
    private static WeakReference<Object> frameScreen = new WeakReference<>(null);

    /** How many different screens frames have been drawn on, which tells a screen from the last. */
    private static double screenSerial = 0.0;

    /**
     * A number that changes whenever a frame is drawn on a different screen object than the frame
     * before it, so an overlay can tell a screen it just opened from the one it drew on last.
     */
    public static double screenSerial() {
        return screenSerial;
    }

    /**
     * Clears the drawn mark when an eligible screen opens, so TestTickContext.overlay_drawn speaks
     * about the screen that is open now rather than about a frame an earlier test drew on an
     * earlier screen. Called by a loader when an eligible screen initializes, on the client thread.
     *
     * <p>It also advances the first-frame generation, so a read between this call and the frame
     * after it cannot describe the frame before it. A frame of the same screen confirms the record
     * at the new generation but keeps the answer its first frame gave, because a hook that ran
     * after that frame cannot make it clean. A new screen object is what starts a new record.
     */
    public static void screenOpened() {
        overlayDrawn = false;
        FIRST_FRAME.opened();
    }

    /** Whether a frame drew the running test's overlay on the screen that is open now. */
    public static boolean overlayDrawn() {
        return overlayDrawn;
    }

    /**
     * Whether a frame has drawn the overlay of {@code mod}, the mod that owns the running test, on
     * the screen that is open now: what {@code TestTickContext.overlay_drawn} answers on a client
     * that runs the test itself, and what the client of a server-driven run answers when the server
     * asks it (#1099).
     *
     * <p>The ask is also what makes the client draw, so a test that never captures still gets the
     * frames its wait is about, as the local call always did. A mod that is not the one the last
     * ask was about starts from a clean mark, because the frame that set it belonged to another
     * mod's overlay and says nothing about this one.
     */
    public static boolean drawnForTest(String mod) {
        noteTestMod(mod);
        GrugFrameRequest.request();
        return overlayDrawn;
    }

    /**
     * Whether the drawn mark was already clear when the first frame of the screen that is open now
     * began, which is what {@code TestTickContext.overlay_first_frame_cleared} answers on the
     * client of a server-driven run (#1391).
     *
     * <p>A client that runs the test itself can read the mark before any frame and see the hook's
     * clearing. A joined client cannot, because it draws frames between the server's open and the
     * server's read, so this answer is the client's own record of the mark at the moment that
     * screen's first frame began: a hook that ran before the frame left it clear there and one that
     * ran after it left the previous screen's mark set. False until a frame of the screen has
     * begun, and false when {@code screenIdentity} names another screen than the record describes,
     * which is what keeps the first screen's clean record from answering for a reopened one.
     *
     * <p>A mod that is not the one the last ask was about starts a new screen from a clean mark, as
     * {@link #drawnForTest} does, because the frame that set the mark belonged to another mod's
     * overlay.
     *
     * @param screenIdentity the identity of the screen open now, from {@link
     *     GrugTestScreens#identityOf}, or 0 when none is open
     */
    public static boolean firstFrameClearedForTest(String mod, long screenIdentity) {
        noteTestMod(mod);
        return FIRST_FRAME.clearedAtFirstFrame(screenIdentity);
    }

    /** Starts a new screen's first-frame record when the mod a read is about changes. */
    private static void noteTestMod(String mod) {
        if (!java.util.Objects.equals(mod, GrugTestState.currentTestMod)) {
            GrugTestState.currentTestMod = mod;
            screenOpened();
            FIRST_FRAME.forget();
        }
    }

    /** Clears the press record, so the press the adapter is about to deliver starts untaken. */
    public static void beginTestPress() {
        testPressTaken = false;
    }

    /** Records that an overlay took the press the adapter is delivering. */
    public static void noteTestPressTaken() {
        testPressTaken = true;
    }

    /** Whether an overlay took the press TestTickContext.click_screen is driving. */
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

    /** The typed character the open key pass was given, see {@code OverlayKeyContext.character}. */
    public static double character() {
        return character;
    }

    /** Whether control was held for the open key pass's key. */
    public static boolean ctrl() {
        return ctrl;
    }

    /** Whether the open key pass is a held key's repeat, see {@code OverlayKeyContext.repeat}. */
    public static boolean repeat() {
        return repeat;
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
     * Opens a key pass for one key press or one typed character, with nothing recorded in it, for
     * the screen the geometry describes. The key is the numbers {@code OverlayKeyContext.key}
     * answers (0 when the press carries no such key), the character is the printable character the
     * press typed (0 when it typed none), {@code ctrl} says whether control was held and {@code
     * repeat} whether the game reported the press as a repeat of a held key.
     */
    public static void openKey(double keyCode, double character, boolean ctrl, boolean repeat) {
        open(true, 0, 0, 0, 0, 0, 0, 0, 0, 0.0, false);
        keyPass = true;
        GrugOverlay.keyCode = keyCode;
        GrugOverlay.character = character;
        GrugOverlay.ctrl = ctrl;
        GrugOverlay.repeat = repeat;
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
        TEXTS.clear();
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

    /**
     * Records one text for the render pass to draw.
     *
     * @return false when no render pass is open. The caller reports that rather than this class.
     */
    public static boolean recordText(String text, double x, double y, int rgb) {
        if (!isRender()) {
            return false;
        }
        TEXTS.add(new GrugScreenText(text, x, y, rgb));
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
                new GrugOverlayGeometry(
                        new ArrayList<>(ITEMS), new ArrayList<>(BOXES), new ArrayList<>(TEXTS));
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
        character = 0.0;
        ctrl = false;
        repeat = false;
        ITEMS.clear();
        BOXES.clear();
        TEXTS.clear();
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
     * Runs an overlay's {@code key()} for one key press or one typed character and answers whether
     * it took it. The loader calls this before its screen sees the event and cancels the event when
     * the answer is true.
     */
    public static boolean key(
            long entityHandle,
            long keyFnId,
            double keyCode,
            double character,
            boolean ctrl,
            boolean repeat) {
        GrugStateLock.lock();
        try {
            openKey(keyCode, character, ctrl, repeat);
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
    static void callExport(
            long entityHandle, long exportFnId, GrugEntityType contextType, Object context) {
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
