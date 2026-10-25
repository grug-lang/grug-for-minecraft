package net.grug.minecraft.grug;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The one block entity's geometry being built right now, and the boxes the draw_box host function
 * recorded while it was.
 *
 * <p>A render pass belongs to the loader, not to a script: the loader opens one before it calls a
 * block entity's render function, closes it afterwards, and draws what was recorded. That is the
 * only place a draw call can go, because the geometry has to end up in whatever the loader's
 * rendering stack is in the middle of building, and only the loader knows what that is.
 *
 * <p>There are two kinds of pass. A chunk pass is opened for {@code render()} and records the
 * shapes the game bakes into a chunk's geometry. A frame pass is opened for {@code render_frame()}
 * and records the stacks drawn on top of that geometry every frame; it carries the frame's partial
 * tick and the direction the camera looks in, which is what lets a mod interpolate between ticks
 * and turn a sprite towards the player. The two kinds cannot be mixed: a chunk pass refuses
 * draw_item, and a frame pass refuses draw_box and draw_model, because neither has anywhere to go.
 *
 * <p>There is at most one open pass at a time. The caller ({@link GrugBlockGeometry}) holds {@link
 * GrugStateLock} across the whole pass, so two loaders on different threads cannot interleave their
 * open/record/close even on a version that rebuilds chunks on worker threads. A script cannot start
 * a loader's render. Opening a second pass anyway is a broken engine invariant rather than a mod
 * mistake, so it throws.
 *
 * <p>Nothing here reports an error itself. A JVM test cannot call into the native adapter that
 * carries {@code Grug.hostFunctionErrorHappened}, so the reporting lives in the caller that can:
 * {@link HostFunctions} for a draw call outside a pass, and {@link GrugBlockGeometry} for a chunk
 * pass that drew nothing.
 */
public final class GrugRenderPass {
    /**
     * The context a pass hands its export, which is what the context's methods check before they
     * draw.
     *
     * <p>A new one is made for every pass and cleared when the pass closes, so a script that saves
     * the context in a member variable and uses it in a later call is refused: the context is no
     * longer the one the open pass was given. The whole point is that the check is about the call,
     * not about the script's good behaviour.
     */
    public static final class Context {
        @GrugGenerated("marker: only a pass makes one")
        private Context() {}
    }

    private static final List<GrugBox> BOXES = new ArrayList<>();
    private static final List<GrugQuad> QUADS = new ArrayList<>();
    private static final List<GrugItemDraw> ITEMS = new ArrayList<>();
    private static boolean open = false;
    private static boolean frame = false;
    private static Context context = null;
    private static double partialTick = 0.0;
    private static double cameraYaw = 0.0;

    @GrugGenerated("utility class: never instantiated")
    private GrugRenderPass() {}

    /** Whether a render pass is open, which is the only state draw_box is legal in. */
    public static boolean isOpen() {
        return open;
    }

    /** Whether the open pass is a frame pass, which is the only state draw_item is legal in. */
    public static boolean isFrame() {
        return open && frame;
    }

    /**
     * Opens a chunk pass, with nothing recorded in it. Nesting is impossible from a script and
     * unreachable from a loader, so reaching it means the engine is broken, and that is reported as
     * an invariant rather than recovered from: a second pass would cross-contaminate one block's
     * boxes into another's geometry, and quietly dropping the first one's boxes would draw the
     * wrong shape without saying so.
     */
    public static void open() {
        open(false, 0.0, 0.0);
    }

    /**
     * Opens a frame pass for one frame, with nothing recorded in it.
     *
     * @param partialTick how much of the current tick has already elapsed, from 0 up to but not
     *     including 1
     * @param cameraYaw the direction the camera looks in, in degrees, as the game's own yaw
     */
    public static void openFrame(double partialTick, double cameraYaw) {
        open(true, partialTick, cameraYaw);
    }

    private static void open(boolean isFrame, double framePartialTick, double frameCameraYaw) {
        if (open) {
            throw Grug.fatal("A block entity render pass is already open.");
        }
        BOXES.clear();
        QUADS.clear();
        ITEMS.clear();
        frame = isFrame;
        context = new Context();
        partialTick = framePartialTick;
        cameraYaw = frameCameraYaw;
        open = true;
    }

    /**
     * The context the open pass was given, which a loader hands to the pass's export, or null when
     * no pass is open.
     */
    public static Context context() {
        return context;
    }

    /** How much of the current tick has already elapsed, in a frame pass. */
    public static double partialTick() {
        return partialTick;
    }

    /** The direction the camera looks in, in degrees, in a frame pass. */
    public static double cameraYaw() {
        return cameraYaw;
    }

    /**
     * Closes a chunk pass and returns what the render function recorded, in the order it drew it.
     *
     * <p>Copies, so the geometry a loader is about to draw cannot be changed by the next pass, and
     * so that closing the pass does not hand out the live lists.
     */
    public static GrugGeometry close() {
        if (frame) {
            throw Grug.fatal(
                    "A block entity render pass was closed as a chunk pass while it was a frame"
                            + " pass.");
        }
        GrugGeometry geometry = new GrugGeometry(new ArrayList<>(BOXES), new ArrayList<>(QUADS));
        finish();
        return geometry;
    }

    /**
     * Closes a frame pass and returns the stacks it drew, in the order it drew them.
     *
     * <p>Copies for the same reason as {@link #close()}.
     */
    public static GrugFrameGeometry closeFrame() {
        if (!frame) {
            throw Grug.fatal(
                    "A block entity render pass was closed as a frame pass while it was a chunk"
                            + " pass.");
        }
        GrugFrameGeometry geometry = new GrugFrameGeometry(new ArrayList<>(ITEMS));
        finish();
        return geometry;
    }

    private static void finish() {
        BOXES.clear();
        QUADS.clear();
        ITEMS.clear();
        frame = false;
        context = null;
        partialTick = 0.0;
        cameraYaw = 0.0;
        open = false;
    }

    /**
     * Reads the model at {@code modelPath} and records its faces turned about the block's centre,
     * about z first, then y, then x, clockwise seen from the positive end of each axis. See {@link
     * GrugQuad#turned}.
     *
     * @return null when the faces were recorded, or what stopped them, which the caller reports. An
     *     angle that is not a multiple of 90 degrees is refused, since a face cannot be turned by
     *     less than a quarter turn without leaving the block's grid.
     */
    public static String recordModel(
            File modsDir,
            String modelPath,
            double xDegrees,
            double yDegrees,
            double zDegrees,
            double dx,
            double dy,
            double dz) {
        if (!open || frame) {
            return "It can only be called while a block entity's render() is drawing.";
        }
        if (!isQuarterTurns(xDegrees) || !isQuarterTurns(yDegrees) || !isQuarterTurns(zDegrees)) {
            return "The angles have to be multiples of 90 degrees, but they were "
                    + xDegrees
                    + ", "
                    + yDegrees
                    + " and "
                    + zDegrees
                    + ".";
        }
        if (!Double.isFinite(dx) || !Double.isFinite(dy) || !Double.isFinite(dz)) {
            return "The offsets have to be finite numbers, but they were "
                    + dx
                    + ", "
                    + dy
                    + " and "
                    + dz
                    + ".";
        }
        try {
            for (GrugQuad quad : GrugModels.load(modsDir, modelPath)) {
                QUADS.add(
                        quad.turned(
                                        (int) (xDegrees / 90),
                                        (int) (yDegrees / 90),
                                        (int) (zDegrees / 90))
                                .moved(dx, dy, dz));
            }
            return null;
        } catch (GrugModels.Defect defect) {
            return defect.getMessage();
        }
    }

    private static boolean isQuarterTurns(double degrees) {
        return degrees % 90.0 == 0.0 && Math.abs(degrees) < 1e6;
    }

    /**
     * Records one box.
     *
     * <p>A box whose far corner is nearer than its near corner is refused rather than drawn,
     * because nothing else would say so. The engine reads the shape as it stands and builds each
     * face quad straight from it, so on a reversed axis the four faces that span it come out wound
     * backwards and back-face culling discards them, while the two faces at its ends survive at
     * swapped depths. The author asked for a box and gets two loose faces where the other four used
     * to be: visibly wrong, and undiagnosed, since the caller's report of a pass that drew nothing
     * cannot catch it either, a reversed box being a box and the pass having recorded something.
     *
     * @return false when there is no open chunk pass, or when the box is the wrong way round. The
     *     caller reports that rather than this class, and nothing is recorded either way. Refusing
     *     does not silence the caller's other report: a pass whose only box was refused records
     *     nothing, so the author reads about the refusal and about the empty result, and both are
     *     true.
     */
    public static boolean record(double x1, double y1, double z1, double x2, double y2, double z2) {
        if (!open || frame) {
            return false;
        }

        GrugBox box = new GrugBox(x1, y1, z1, x2, y2, z2);
        if (!isOrdered(box)) {
            return false;
        }

        BOXES.add(box);
        return true;
    }

    /**
     * Records one stack for the frame pass to draw.
     *
     * @return false when no frame pass is open. The caller reports that rather than this class, so
     *     the message names the call and where it is legal.
     */
    public static boolean recordItem(
            Object item, int damage, int count, double x, double y, double z) {
        if (!isFrame()) {
            return false;
        }

        ITEMS.add(new GrugItemDraw(item, damage, count, x, y, z));
        return true;
    }

    /**
     * Whether a box's corners are the right way round, which is what makes it a box.
     *
     * <p>The first of each pair is the near corner and has to be no greater than the second.
     *
     * <p>The range is deliberately not part of this. The game draws a box reaching past its block
     * unclipped, so an author gets the arithmetic they wrote rather than a shape grug chose, which
     * is the one thing this API never does. Zero thickness on an axis is not a mistake either: a
     * plate is a shape, and this rule is about which corner comes first, not about how far apart
     * the two are.
     */
    public static boolean isOrdered(GrugBox box) {
        return box.x1() <= box.x2() && box.y1() <= box.y2() && box.z1() <= box.z2();
    }

    /**
     * The boxes recorded so far, in the order they were drawn.
     *
     * <p>What a loader draws, and what a shape is built from one box at a time.
     */
    public static List<GrugBox> recorded() {
        return Collections.unmodifiableList(new ArrayList<>(BOXES));
    }
}
