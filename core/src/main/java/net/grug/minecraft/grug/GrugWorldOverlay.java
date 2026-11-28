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

    /**
     * One filled triangle of a world pass: three corners in world coordinates and the color as
     * 0xAARRGGBB, where the alpha is how opaque it is.
     */
    public static final class Triangle {
        private final double[] corners;
        private final int argb;

        Triangle(double[] corners, int argb) {
            this.corners = corners;
            this.argb = argb;
        }

        /**
         * The corner's coordinate: {@code corner} is 0, 1 or 2 and {@code axis} 0 for x, 1 y, 2 z.
         */
        public double corner(int corner, int axis) {
            return corners[corner * 3 + axis];
        }

        /** The color as 0xAARRGGBB. */
        public int argb() {
            return argb;
        }
    }

    private enum Kind {
        NONE,
        HUD,
        WORLD,
        KEY,
        BINDING
    }

    private static final List<Text> TEXTS = new ArrayList<>();
    private static final List<Line> LINES = new ArrayList<>();
    private static final List<Triangle> TRIANGLES = new ArrayList<>();
    private static List<Triangle> lastTriangles = Collections.emptyList();
    private static boolean trianglesSupported = false;

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
    private static double playerX = 0.0;
    private static double playerY = 0.0;
    private static double playerZ = 0.0;
    private static boolean depthTest = true;
    private static String mod = null;
    private static String bindingId = null;

    /** The held state of every declared binding, so a poll reports a press and not a hold. */
    private static final GrugKeyBindings bindings = new GrugKeyBindings();

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

    /** Whether the open pass is a binding pass. */
    public static boolean isBinding() {
        return kind == Kind.BINDING;
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

    /**
     * The local player's feet this frame, the position a scan of the world is centred on. A loader
     * passes the player's own non-interpolated position, not the camera that is interpolated for
     * the draw, and the scan floors it to the block the feet are in.
     */
    public static double playerX() {
        return playerX;
    }

    public static double playerY() {
        return playerY;
    }

    public static double playerZ() {
        return playerZ;
    }

    /**
     * The depth test the frame's world geometry asks to be drawn with, on by default. It is the
     * value in effect when the export returned: a loader reads it after {@link #world} and draws
     * that pass's lines and triangles with it, restoring the game's own depth-test state
     * afterwards. It is reset when the next world pass opens, so a script that asks for one value
     * leaves it for no other script.
     */
    public static boolean depthTest() {
        return depthTest;
    }

    /**
     * Records what the open world pass asks its geometry to be drawn with, false for see-through.
     */
    public static void requestDepthTest(boolean enabled) {
        depthTest = enabled;
    }

    /** The mod whose export is running in the open pass, or null outside one. */
    public static String mod() {
        return mod;
    }

    /** The binding id the open world_binding pass was called for, or null outside one. */
    public static String bindingId() {
        return bindingId;
    }

    private static void open(Kind opened) {
        if (kind != Kind.NONE) {
            throw Grug.fatal("A world overlay pass is already open.");
        }
        TEXTS.clear();
        LINES.clear();
        TRIANGLES.clear();
        context = new Context();
        kind = opened;
    }

    /** Opens the HUD pass for one frame, with the scaled size of the GUI layer. */
    public static void openHud(double width, double height) {
        open(Kind.HUD);
        screenWidth = width;
        screenHeight = height;
    }

    /**
     * Opens the world pass for one frame, with the camera the lines are seen from, the local
     * player's feet, and the mod whose overlay is being asked.
     */
    public static void openWorld(
            double tick,
            double x,
            double y,
            double z,
            double feetX,
            double feetY,
            double feetZ,
            String callerMod) {
        open(Kind.WORLD);
        // Every world export starts depth-tested: a pass whose script asks for nothing keeps the
        // game's own value, and one script's request is never the next script's.
        depthTest = true;
        partialTick = tick;
        cameraX = x;
        cameraY = y;
        cameraZ = z;
        playerX = feetX;
        playerY = feetY;
        playerZ = feetZ;
        mod = callerMod;
    }

    /** Opens a key pass for one press of the key whose upper case character code is given. */
    public static void openKey(double code, boolean controlHeld) {
        open(Kind.KEY);
        keyCode = code;
        ctrl = controlHeld;
    }

    /** Opens the pass for one press of the binding {@code id} of the mod {@code callerMod}. */
    public static void openBinding(String callerMod, String id) {
        open(Kind.BINDING);
        mod = callerMod;
        bindingId = id;
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

    /**
     * Records one translucent triangle for the world pass, false when no world pass is open. The
     * caller has checked {@link #trianglesSupported()}.
     */
    public static boolean recordTriangle(double[] corners, int argb) {
        if (kind != Kind.WORLD) {
            return false;
        }
        TRIANGLES.add(new Triangle(corners, argb));
        return true;
    }

    /**
     * Whether this loader draws the triangles of a world pass. Every loader says yes once its
     * overlay host has started (#1427).
     */
    public static boolean trianglesSupported() {
        return trianglesSupported;
    }

    /** A loader that draws the triangles of a world pass says so once, when it starts. */
    public static void noteTrianglesSupported() {
        trianglesSupported = true;
    }

    /**
     * The triangles the last {@link #closeWorld} pass recorded, in the order they were drawn: a
     * loader that draws them reads this right after the pass.
     */
    public static List<Triangle> lastTriangles() {
        return lastTriangles;
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
        lastTriangles = Collections.unmodifiableList(new ArrayList<>(TRIANGLES));
        finish();
        return Collections.unmodifiableList(drawnLines);
    }

    /** Closes a key pass. */
    public static void closeKey() {
        requireKind(Kind.KEY);
        finish();
    }

    /** Closes a binding pass. */
    public static void closeBinding() {
        requireKind(Kind.BINDING);
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
        TRIANGLES.clear();
        kind = Kind.NONE;
        context = null;
        keyCode = 0.0;
        ctrl = false;
        playerX = 0.0;
        playerY = 0.0;
        playerZ = 0.0;
        mod = null;
        bindingId = null;
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

    /**
     * Runs an overlay's {@code render_world()} for one frame with the camera, the local player's
     * feet and the mod the overlay belongs to, and answers the lines it drew.
     */
    public static List<Line> world(
            long entityHandle,
            long fnId,
            double tick,
            double x,
            double y,
            double z,
            double feetX,
            double feetY,
            double feetZ,
            String callerMod) {
        GrugStateLock.lock();
        try {
            openWorld(tick, x, y, z, feetX, feetY, feetZ, callerMod);
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

    /**
     * Polls one declared binding and asks the mod's overlays' {@code world_binding()} when it just
     * went down in a world with no screen open, running the first overlay that exports it. The
     * loader calls this every tick with the binding's held state, and a held binding is one press.
     *
     * <p>The engine's export id is looked up by entity type and function name, not by file, so it
     * cannot say which of the mod's overlay files exports the function. Every one of them is asked
     * in clean-name order and the first answer ends the pass, which is what makes the mod's second
     * overlay its handler when the first one does not export {@code world_binding} (#1749's
     * review).
     *
     * @param gated whether a world is shown and no screen is open
     */
    public static void binding(
            List<GrugOverlayScripts.Script> scripts,
            String callerMod,
            String id,
            boolean down,
            boolean gated) {
        if (!bindings.deliver(callerMod, id, down, gated)) return;
        GrugStateLock.lock();
        try {
            openBinding(callerMod, id);
            try {
                for (GrugOverlayScripts.Script script : scripts) {
                    if (callAnswering(script.entityHandle, script.worldBindingFnId)) return;
                }
            } finally {
                closeBinding();
            }
        } finally {
            GrugStateLock.unlock();
        }
    }

    /**
     * Presses, once, every binding of every mod among {@code scripts} whose declared default key is
     * {@code code}. A loader with no way to register a binding polls raw keys and calls this, which
     * is how its default key still works; a loader that registers the binding polls {@link
     * #binding} instead, so a rebound key is the one that fires.
     *
     * <p>{@code onlyMod} limits the press to that mod's bindings, and {@code null} is every mod,
     * which is what a real key press does.
     */
    public static void bindingDefaultsFor(
            List<GrugOverlayScripts.Script> scripts, String onlyMod, int code) {
        List<String> mods = new ArrayList<>();
        for (GrugOverlayScripts.Script script : scripts) {
            if (onlyMod != null && !onlyMod.equals(script.mod)) continue;
            if (!mods.contains(script.mod)) mods.add(script.mod);
        }
        for (String mod : mods) {
            List<GrugOverlayScripts.Script> modScripts = GrugOverlayScripts.bindingScripts(mod);
            for (GrugMetadata.Key key : GrugMetadata.forCallerMod(mod).keys()) {
                if (key.code() != code) continue;
                binding(modScripts, mod, key.id(), true, true);
                binding(modScripts, mod, key.id(), false, true);
            }
        }
    }

    /** {@link #bindingDefaultsFor(List, String, int)} for a real press, which every mod sees. */
    public static void bindingDefaultsFor(List<GrugOverlayScripts.Script> scripts, int code) {
        bindingDefaultsFor(scripts, null, code);
    }

    /**
     * Test only: delivers one press of {@code code} to {@code mod} alone, both to its {@code
     * world_key()} exports and to its own bindings whose declared default key is {@code code}. A
     * real press reaches every mod, but a test press drives the mod under test: another mod that
     * happens to bind the same key would otherwise change its own state in a test that never meant
     * to touch it, and that change would show up in a later test's world or capture (#1873).
     */
    public static void pressForTest(
            List<GrugOverlayScripts.Script> scripts, String mod, double code, boolean ctrl) {
        for (GrugOverlayScripts.Script overlay : scripts) {
            if (mod != null && !mod.equals(overlay.mod)) continue;
            key(overlay.entityHandle, overlay.worldKeyFnId, code, ctrl);
        }
        bindingDefaultsFor(GrugOverlayScripts.bindingScripts(), mod, (int) code);
    }

    /**
     * Test only: delivers one press of the binding {@code mod} declares as {@code id}, through the
     * same {@link #binding} path a real press takes, and only to that mod. A binding whose
     * declaration has no {@code default_key} is registered with the version's unbound key, which no
     * real key press can reach, so this is how a test presses such a binding; an id the mod does
     * not declare is the caller's to refuse before it gets here. It clears the drawn mark first, as
     * a real press does, so a test waits for a newer frame.
     */
    public static void pressBindingForTest(String mod, String id) {
        clearDrawn();
        List<GrugOverlayScripts.Script> scripts = GrugOverlayScripts.bindingScripts(mod);
        binding(scripts, mod, id, true, true);
        binding(scripts, mod, id, false, true);
    }

    private static boolean callAnswering(long entityHandle, long fnId) {
        if (entityHandle == 0 || fnId == Grug.INVALID_GRUG_EXPORT_FN_ID) return false;
        return GrugOverlay.callExport(
                entityHandle, fnId, GrugEntityType.OverlayWorldBindingContext, context);
    }

    private static void call(long entityHandle, long fnId, GrugEntityType contextType) {
        if (entityHandle != 0 && fnId != Grug.INVALID_GRUG_EXPORT_FN_ID) {
            GrugOverlay.callExport(entityHandle, fnId, contextType, context);
        }
    }
}
