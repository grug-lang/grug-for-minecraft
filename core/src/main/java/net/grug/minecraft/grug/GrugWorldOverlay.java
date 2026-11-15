package net.grug.minecraft.grug;

import net.grug.minecraft.core.GrugFrameRequest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The overlay passes that run while no screen is open: a key press in the world, the text drawn on
 * the frame, and the lines drawn in the world, and what their scripts recorded.
 *
 * <p>{@link GrugOverlay} is the overlay of a container screen, with the geometry of that screen and
 * a click it may take. These three have no screen: the key is the player's own key press while
 * playing, the text lands on the frame's GUI layer, and the lines land in the world after the
 * game's own particles, tied to no block. They are kept apart so a pass of one kind cannot read the
 * numbers of another.
 *
 * <p>A pass belongs to the loader, which opens it before it asks an overlay's export, closes it
 * afterwards and draws what was recorded. There is at most one open pass at a time, and the caller
 * holds {@link GrugStateLock} across it, like every other pass. Nothing here reports an error
 * itself: a context method called outside its pass is reported by {@link HostFunctions}.
 */
public final class GrugWorldOverlay {
    /**
     * The context a pass hands its export, which is what the context's methods check before they
     * act. A new one is made for every pass and cleared when it closes, so a script that saves it
     * and uses it in a later call is refused.
     */
    public static final class Context {
        @GrugGenerated("marker: only a pass makes one")
        private Context() {}
    }

    /** One text the HUD pass recorded: the string, a position in scaled GUI pixels, and a color. */
    public static final class Text {
        private final String text;
        private final double x;
        private final double y;
        private final int rgb;

        public Text(String text, double x, double y, int rgb) {
            this.text = text;
            this.x = x;
            this.y = y;
            this.rgb = rgb;
        }

        public String text() {
            return text;
        }

        public double x() {
            return x;
        }

        public double y() {
            return y;
        }

        /** The color as 0xRRGGBB. */
        public int rgb() {
            return rgb;
        }
    }

    /** One line the world pass recorded, between two world positions, in a color. */
    public static final class Line {
        private final double x1;
        private final double y1;
        private final double z1;
        private final double x2;
        private final double y2;
        private final double z2;
        private final int rgb;

        public Line(double x1, double y1, double z1, double x2, double y2, double z2, int rgb) {
            this.x1 = x1;
            this.y1 = y1;
            this.z1 = z1;
            this.x2 = x2;
            this.y2 = y2;
            this.z2 = z2;
            this.rgb = rgb;
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

        /** The color as 0xRRGGBB. */
        public int rgb() {
            return rgb;
        }
    }

    private enum Kind {
        NONE,
        HUD,
        WORLD,
        KEY
    }

    private static final List<Text> TEXTS = new ArrayList<>();
    private static final List<Line> LINES = new ArrayList<>();

    private static Kind kind = Kind.NONE;
    private static Context context = null;
    private static double screenWidth = 0.0;
    private static double screenHeight = 0.0;
    private static double partialTick = 0.0;
    private static double cameraX = 0.0;
    private static double cameraY = 0.0;
    private static double cameraZ = 0.0;
    private static double keyCode = 0.0;
    private static boolean ctrl = false;

    /** Set by a loader that registers the three hooks, which is what the test's probe answers. */
    private static volatile boolean supported = false;

    /** Set when a frame drew a non-empty HUD or world pass of the running test's mod. */
    private static volatile boolean drawn = false;

    @GrugGenerated("utility class: never instantiated")
    private GrugWorldOverlay() {}

    /** Marks that this loader asks the three exports, so a test can skip where it does not. */
    public static void noteSupported() {
        supported = true;
    }

    /** Whether this loader asks the world key, HUD and world exports at all. */
    public static boolean supported() {
        return supported;
    }

    /** Marks that a frame drew something of the running test's mod. */
    public static void noteDrawn() {
        drawn = true;
    }

    /** Forgets the mark, which the test does as it presses a key so it waits for a newer frame. */
    public static void clearDrawn() {
        drawn = false;
    }

    /**
     * Whether a frame drew the running test's overlay since the mark was last cleared. Asking also
     * asks the client for a frame, because the runner skips the frames of a test that never
     * captures.
     */
    public static boolean drawnForTest() {
        GrugFrameRequest.request();
        return drawn;
    }

    /** Whether the open pass is the HUD pass, the only state a text draw is legal in. */
    public static boolean isHud() {
        return kind == Kind.HUD;
    }

    /** Whether the open pass is the world pass, the only state a line draw is legal in. */
    public static boolean isWorld() {
        return kind == Kind.WORLD;
    }

    /** Whether the open pass is a key pass, the only state the key's methods are legal in. */
    public static boolean isKey() {
        return kind == Kind.KEY;
    }

    /** Whether any pass is open. */
    public static boolean isOpen() {
        return kind != Kind.NONE;
    }

    /** The context the open pass was given, or null when no pass is open. */
    public static Context context() {
        return context;
    }

    public static double screenWidth() {
        return screenWidth;
    }

    public static double screenHeight() {
        return screenHeight;
    }

    public static double partialTick() {
        return partialTick;
    }

    public static double cameraX() {
        return cameraX;
    }

    public static double cameraY() {
        return cameraY;
    }

    public static double cameraZ() {
        return cameraZ;
    }

    public static double keyCode() {
        return keyCode;
    }

    public static boolean ctrl() {
        return ctrl;
    }

    private static void open(Kind opened) {
        if (kind != Kind.NONE) {
            throw Grug.fatal("A world overlay pass is already open.");
        }
        TEXTS.clear();
        LINES.clear();
        context = new Context();
        kind = opened;
    }

    /** Opens the HUD pass for one frame, with the scaled size of the GUI layer. */
    public static void openHud(double width, double height) {
        open(Kind.HUD);
        screenWidth = width;
        screenHeight = height;
    }

    /** Opens the world pass for one frame, with the camera the lines are seen from. */
    public static void openWorld(double tick, double x, double y, double z) {
        open(Kind.WORLD);
        partialTick = tick;
        cameraX = x;
        cameraY = y;
        cameraZ = z;
    }

    /** Opens a key pass for one press of the key whose upper case character code is given. */
    public static void openKey(double code, boolean controlHeld) {
        open(Kind.KEY);
        keyCode = code;
        ctrl = controlHeld;
    }

    /** Records one text for the HUD pass, false when no HUD pass is open. */
    public static boolean recordText(String text, double x, double y, int rgb) {
        if (kind != Kind.HUD) {
            return false;
        }
        TEXTS.add(new Text(text, x, y, rgb));
        return true;
    }

    /** Records one line for the world pass, false when no world pass is open. */
    public static boolean recordLine(
            double x1, double y1, double z1, double x2, double y2, double z2, int rgb) {
        if (kind != Kind.WORLD) {
            return false;
        }
        LINES.add(new Line(x1, y1, z1, x2, y2, z2, rgb));
        return true;
    }

    /** Closes the HUD pass and returns the texts it recorded, in the order they were drawn. */
    public static List<Text> closeHud() {
        requireKind(Kind.HUD);
        List<Text> drawnTexts = new ArrayList<>(TEXTS);
        finish();
        return Collections.unmodifiableList(drawnTexts);
    }

    /** Closes the world pass and returns the lines it recorded, in the order they were drawn. */
    public static List<Line> closeWorld() {
        requireKind(Kind.WORLD);
        List<Line> drawnLines = new ArrayList<>(LINES);
        finish();
        return Collections.unmodifiableList(drawnLines);
    }

    /** Closes a key pass. */
    public static void closeKey() {
        requireKind(Kind.KEY);
        finish();
    }

    private static void requireKind(Kind expected) {
        if (kind != expected) {
            throw Grug.fatal(
                    "A world overlay pass was closed as a "
                            + expected
                            + " pass while it was a "
                            + kind
                            + " pass.");
        }
    }

    private static void finish() {
        TEXTS.clear();
        LINES.clear();
        kind = Kind.NONE;
        context = null;
        keyCode = 0.0;
        ctrl = false;
    }

    /**
     * Runs an overlay's {@code render_hud()} for one frame and answers the texts it drew. A handle
     * of zero means the mod has no overlay script, and an invalid export id means the script has no
     * such export, so neither reaches the VM, and the pass still opens and closes.
     */
    public static List<Text> hud(long entityHandle, long fnId, double width, double height) {
        GrugStateLock.lock();
        try {
            openHud(width, height);
            List<Text> recorded;
            try {
                call(entityHandle, fnId, GrugEntityType.OverlayHudContext);
            } finally {
                // Closing in finally keeps the next frame from hitting the nesting check when a
                // fatal raised inside the script comes back as an exception.
                recorded = closeHud();
            }
            return recorded;
        } finally {
            GrugStateLock.unlock();
        }
    }

    /** Runs an overlay's {@code render_world()} for one frame and answers the lines it drew. */
    public static List<Line> world(
            long entityHandle, long fnId, double tick, double x, double y, double z) {
        GrugStateLock.lock();
        try {
            openWorld(tick, x, y, z);
            List<Line> recorded;
            try {
                call(entityHandle, fnId, GrugEntityType.OverlayWorldContext);
            } finally {
                // Closing in finally keeps the next frame from hitting the nesting check when a
                // fatal raised inside the script comes back as an exception.
                recorded = closeWorld();
            }
            return recorded;
        } finally {
            GrugStateLock.unlock();
        }
    }

    /** Runs an overlay's {@code world_key()} for one key press. */
    public static void key(long entityHandle, long fnId, double code, boolean controlHeld) {
        GrugStateLock.lock();
        try {
            openKey(code, controlHeld);
            try {
                call(entityHandle, fnId, GrugEntityType.OverlayWorldKeyContext);
            } finally {
                closeKey();
            }
        } finally {
            GrugStateLock.unlock();
        }
    }

    private static void call(long entityHandle, long fnId, GrugEntityType contextType) {
        if (entityHandle != 0 && fnId != Grug.INVALID_GRUG_EXPORT_FN_ID) {
            GrugOverlay.callExport(entityHandle, fnId, contextType, context);
        }
    }
}
