package net.grug.minecraft.grug;

import net.grug.minecraft.core.ModLoaderAdapter;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * The screens the overlay scripts created, which one of them is open, and the export calls that
 * make a screen's calls into its script.
 *
 * <p>A screen belongs to one overlay script: {@code Screen.new()} runs in that script's member
 * scope, so the widgets it declares are rooted in the script's owner list and its exports are the
 * script's own {@code screen_*} functions. The model and this lifecycle are the same on every
 * loader; what a loader supplies is its screen subclass, the widgets it mirrors this model into,
 * the input plumbing and the drawing.
 */
public final class GrugOwnedScreens {

    /** The way one export of the screen's own script is called. */
    interface Calls {
        boolean callContext(
                long entityHandle, long exportFnId, GrugEntityType contextType, Object context);

        boolean callWithId(long entityHandle, long exportFnId, long argumentId);

        boolean callNoArgs(long entityHandle, long exportFnId);
    }

    /** The live grug state, which is how a real game calls a screen's exports. */
    static final Calls NATIVE =
            new Calls() {
                @Override
                public boolean callContext(
                        long entityHandle,
                        long exportFnId,
                        GrugEntityType contextType,
                        Object context) {
                    return GrugOverlay.callExport(entityHandle, exportFnId, contextType, context);
                }

                @Override
                public boolean callWithId(long entityHandle, long exportFnId, long argumentId) {
                    boolean[] answered = {false};
                    GrugEntities.withFreshOwner(
                            () ->
                                    answered[0] =
                                            Grug.callExportFn(
                                                    entityHandle, exportFnId, argumentId));
                    return answered[0];
                }

                @Override
                public boolean callNoArgs(long entityHandle, long exportFnId) {
                    boolean[] answered = {false};
                    GrugEntities.withFreshOwner(
                            () -> answered[0] = Grug.callExportFn(entityHandle, exportFnId));
                    return answered[0];
                }
            };

    /**
     * What the exports are called through. A unit test replaces it to record the calls a lifecycle
     * makes, which no game test can observe from Java.
     */
    private static Calls calls = NATIVE;

    /** The script whose member scope is running, which is what {@code Screen.new()} binds to. */
    private static GrugOverlayScripts.Script creating = null;

    /** The screens every overlay script created, in creation order. */
    private static final List<GrugOwnedScreen> screens = new ArrayList<>();

    /** The screen that is open, or null when none is. */
    private static volatile GrugOwnedScreen openScreen = null;

    /** The {@code screen_init} call that is running, or null outside one. */
    private static volatile GrugScreenInitContext initContext = null;

    /** The {@code screen_widget_changed} call that is running, or null outside one. */
    private static volatile GrugScreenWidgetContext widgetContext = null;

    /**
     * The rows {@code screen_list_row} answered for the frame that was last prepared.
     *
     * <p>A response is rooted here until the next frame prepares, so a mod that saves the response
     * and uses it after its call is refused by {@link GrugResponse} with the rule it broke, rather
     * than with a collected id. The list is dropped at the start of the next frame, which bounds it
     * to the rows one frame showed.
     */
    private static final List<GrugObject> frameRowRoots = new ArrayList<>();

    @GrugGenerated("utility class: never instantiated")
    private GrugOwnedScreens() {}

    // --- Creation and the script it belongs to ------------------------------------------------

    /**
     * Marks the overlay script whose member scope is about to run, so a {@code Screen.new()} in it
     * knows which script it belongs to. The entity handle is filled in once the script's file has
     * run, which is why the binding is the script itself rather than a finished one.
     *
     * <p>This is the same binding {@code GrugEntities.currentlyInitializingBlockEntity} does for a
     * block entity's member scope, and the race is closed the same way: {@link
     * GrugOverlayScripts#ensureCreated} holds {@link GrugStateLock} across this swap, the entity's
     * creation and the restore, so no other thread's member scope can run between them and bind its
     * screen to this script.
     */
    static void beginScriptCreation(GrugOverlayScripts.Script script) {
        creating = script;
    }

    static void endScriptCreation() {
        creating = null;
    }

    /** What {@code Screen.new()} made, or why it could not. */
    static final class Created {
        final GrugOwnedScreen screen;
        final String problem;

        Created(GrugOwnedScreen screen, String problem) {
            this.screen = screen;
            this.problem = problem;
        }
    }

    /**
     * Creates a screen for the overlay script whose member scope is running.
     *
     * <p>The binding is the script being created and not "the last script", so a screen made by a
     * block entity's or a test's member scope has no script to belong to and is refused rather than
     * written into another file's owner list. See {@code
     * GrugEntities.currentlyInitializingBlockEntity} for the same binding-around-creation race on
     * the block entity side.
     */
    static Created newScreen(ModLoaderAdapter adapter) {
        if (adapter == null || !adapter.ownedScreensSupported()) {
            return new Created(
                    null,
                    "Screen.new: This loader has no mod-owned screens, so a mod cannot make one.");
        }
        GrugOverlayScripts.Script script = creating;
        if (script == null) {
            return new Created(
                    null,
                    "Screen.new: A screen is made at member scope of an overlay script, in the"
                            + " call that script makes when it loads, and this call is not inside"
                            + " one.");
        }
        GrugOwnedScreen screen = new GrugOwnedScreen(script);
        screens.add(screen);
        return new Created(screen, null);
    }

    /**
     * Drops the screens of an overlay script that is going away, closing the open one first so a
     * mod hears {@code screen_closed} while its script still runs.
     */
    static void scriptDropped(GrugOverlayScripts.Script script) {
        for (Iterator<GrugOwnedScreen> iterator = screens.iterator(); iterator.hasNext(); ) {
            GrugOwnedScreen screen = iterator.next();
            if (screen.script() != script) continue;
            if (openScreen == screen) {
                endOpenTime(screen);
            }
            GrugEntities.removeEntity(screen.entityId(), script.owner);
            iterator.remove();
        }
    }

    // --- Playing a screen ---------------------------------------------------------------------

    /** Why {@code screen} cannot be opened, or null when it was. */
    static String open(ModLoaderAdapter adapter, GrugOwnedScreen screen) {
        if (adapter == null || !adapter.ownedScreensSupported()) {
            return "Screen.open: This loader has no mod-owned screens, so a mod cannot open one.";
        }
        if (adapter.clientLevel() == null) {
            return "Screen.open: Opening a screen while no world is loaded is an err, because a"
                    + " mod screen is drawn over the world and there is nothing to draw it over.";
        }
        // A screen_closed may open another screen, so the loop closes whatever is open until
        // nothing is, which is also what reopening the screen that is already open does: its
        // screen_closed runs before this one's screen_init.
        while (openScreen != null) {
            closeOpen(adapter);
        }
        screen.setOpen(true);
        openScreen = screen;
        adapter.showOwnedScreen(screen);
        return null;
    }

    /** Why {@code screen} cannot be closed, or null when it was. */
    static String close(ModLoaderAdapter adapter, GrugOwnedScreen screen) {
        if (screen != openScreen) {
            return "Screen.close: This screen is not the one that is open, so closing it would"
                    + " either do nothing or close the screen that is open instead.";
        }
        closeOpen(adapter);
        return null;
    }

    private static void closeOpen(ModLoaderAdapter adapter) {
        GrugOwnedScreen screen = openScreen;
        endOpenTime(screen);
        adapter.hideOwnedScreen(screen);
    }

    /**
     * Ends a screen's open time: it is no longer the open screen, its widgets are gone, and its
     * script hears {@code screen_closed} exactly once.
     */
    private static void endOpenTime(GrugOwnedScreen screen) {
        openScreen = null;
        screen.setOpen(false);
        screen.dropWidgets();
        askClosed(screen.script());
    }

    /**
     * The game took a screen away on its own (Escape, the world being left, or another screen
     * replacing it), which is the same end as {@link #close} without the loader being told to hide
     * a screen the game already hid.
     *
     * <p>The game calls this from its own thread, so it takes the state lock: the server thread may
     * be inside a grug call, and dropping the screen's widgets touches the entity table the call
     * reads.
     */
    public static void gameRemoved(GrugOwnedScreen screen) {
        GrugEntities.runLocked(
                () -> {
                    if (screen != openScreen) return;
                    endOpenTime(screen);
                });
    }

    /**
     * The world went away while a screen was open, so the screen goes with it. A loader calls this
     * before the game tears the screen down, so the mod hears {@code screen_closed} while the world
     * it draws over still exists.
     */
    public static void worldUnloaded() {
        GrugEntities.runLocked(
                () -> {
                    if (openScreen == null) return;
                    endOpenTime(openScreen);
                });
    }

    /** The screen that is open, or null when none is. */
    public static GrugOwnedScreen openScreen() {
        return openScreen;
    }

    // --- The screen's own calls ---------------------------------------------------------------

    /** Whether {@code screen}'s {@code screen_init} is the call that is running now. */
    static boolean isInitializing(GrugOwnedScreen screen) {
        GrugScreenInitContext context = initContext;
        return context != null && context.screen() == screen;
    }

    static GrugScreenInitContext initContext() {
        return initContext;
    }

    static GrugScreenWidgetContext widgetContext() {
        return widgetContext;
    }

    /**
     * Runs the screen's {@code screen_init} for the size the loader gave it, which is what lays its
     * widgets out when it opens and after every resize.
     */
    public static void initScreen(GrugOwnedScreen screen, double width, double height) {
        if (screen != openScreen) return;
        GrugStateLock.lock();
        try {
            screen.beginInit();
            GrugScreenInitContext enclosingContext = initContext;
            GrugScreenInitContext context = new GrugScreenInitContext(screen, width, height);
            initContext = context;
            try {
                long fnId = screen.script().screenInitFnId;
                if (canAsk(screen.script(), fnId)) {
                    calls.callContext(
                            screen.script().entityHandle,
                            fnId,
                            GrugEntityType.ScreenInitContext,
                            context);
                }
            } finally {
                initContext = enclosingContext;
                screen.endInit();
            }
        } finally {
            GrugStateLock.unlock();
        }
    }

    /** Asks the screen's {@code screen_render} for one frame, after its widgets were drawn. */
    public static GrugOverlayGeometry renderScreen(
            GrugOwnedScreen screen, double width, double height, double mouseX, double mouseY) {
        if (screen != openScreen) return GrugOverlayGeometry.EMPTY;
        GrugOverlayScripts.Script script = screen.script();
        return GrugOverlay.render(
                script.entityHandle,
                script.screenRenderFnId,
                width,
                height,
                0.0,
                0.0,
                width,
                height,
                mouseX,
                mouseY);
    }

    /** Asks the screen's {@code screen_click} for one press and answers whether it took it. */
    public static boolean clickScreen(
            GrugOwnedScreen screen,
            double width,
            double height,
            double mouseX,
            double mouseY,
            double button,
            boolean shift) {
        if (screen != openScreen) return false;
        GrugOverlayScripts.Script script = screen.script();
        return GrugOverlay.click(
                script.entityHandle,
                script.screenClickFnId,
                width,
                height,
                0.0,
                0.0,
                width,
                height,
                mouseX,
                mouseY,
                button,
                shift);
    }

    /** Asks the screen's {@code screen_key} for one key and answers whether it took it. */
    public static boolean keyScreen(
            GrugOwnedScreen screen,
            double key,
            double character,
            boolean ctrl,
            boolean shift,
            boolean repeat) {
        if (screen != openScreen) return false;
        GrugOverlayScripts.Script script = screen.script();
        return GrugOverlay.key(
                script.entityHandle, script.screenKeyFnId, key, character, ctrl, shift, repeat);
    }

    /** Asks the screen's {@code screen_scroll} for one movement and answers whether it took it. */
    public static boolean scrollScreen(
            GrugOwnedScreen screen,
            double width,
            double height,
            double mouseX,
            double mouseY,
            double delta) {
        if (screen != openScreen) return false;
        GrugOverlayScripts.Script script = screen.script();
        return GrugOverlay.scroll(
                script.entityHandle,
                script.screenScrollFnId,
                width,
                height,
                0.0,
                0.0,
                width,
                height,
                mouseX,
                mouseY,
                delta);
    }

    /**
     * Asks {@code screen_list_row} for every row of every visible list that this frame draws, so
     * the loader can draw them next. The answers replace the rows the previous frame got.
     */
    public static void prepareFrame(GrugOwnedScreen screen) {
        if (screen != openScreen) return;
        GrugStateLock.lock();
        try {
            GrugEntities.clearRoots(frameRowRoots);
            for (GrugWidget widget : screen.widgets()) {
                widget.clearFrameRows();
            }
            for (GrugWidget widget : screen.widgets()) {
                if (!widget.is(GrugWidgetKind.LIST) || !widget.visible()) continue;
                int rows = widget.rows();
                if (rows <= 0) continue;
                int first = GrugListMath.firstVisible(widget.scroll(), rows, widget.rowHeight());
                int last =
                        GrugListMath.lastVisible(
                                widget.scroll(), rows, widget.rowHeight(), widget.height());
                for (int index = first; index <= last; index++) {
                    askRow(screen, widget, index);
                }
            }
        } finally {
            GrugStateLock.unlock();
        }
    }

    // --- The player using a widget ------------------------------------------------------------

    /** The player pressed a button that is active and visible. */
    public static void buttonPressed(GrugOwnedScreen screen, GrugWidget widget) {
        if (!usable(screen, widget)) return;
        askWidgetChanged(screen, widget);
    }

    /** The player ticked or unticked a checkbox. */
    public static void checkboxChanged(GrugOwnedScreen screen, GrugWidget widget, boolean checked) {
        if (!usable(screen, widget)) return;
        if (widget.applyChecked(checked)) {
            askWidgetChanged(screen, widget);
        }
    }

    /** The player dragged a slider or clicked its track, from the fraction of it they left. */
    public static void sliderMoved(GrugOwnedScreen screen, GrugWidget widget, double fraction) {
        if (!usable(screen, widget)) return;
        if (widget.applySliderFraction(fraction)) {
            askWidgetChanged(screen, widget);
        }
    }

    /** The player clicked a list row. */
    public static void listRowClicked(GrugOwnedScreen screen, GrugWidget widget, int index) {
        if (!usable(screen, widget)) return;
        if (widget.applyListClick(index)) {
            askWidgetChanged(screen, widget);
        }
    }

    /** The player edited a text field. */
    public static void textEdited(GrugOwnedScreen screen, GrugWidget widget, String text) {
        if (!usable(screen, widget)) return;
        if (widget.applyText(text)) {
            askWidgetChanged(screen, widget);
        }
    }

    /** The wheel moved over a list, which scrolls it silently and answers whether it moved. */
    public static boolean scrollList(GrugOwnedScreen screen, GrugWidget widget, double delta) {
        if (!usable(screen, widget)) return false;
        return widget.applyScroll(delta);
    }

    /** The list under the point, in declaration order, or null when no visible list is there. */
    public static GrugWidget listAt(GrugOwnedScreen screen, double x, double y) {
        for (GrugWidget widget : screen.widgets()) {
            if (!widget.is(GrugWidgetKind.LIST) || !widget.visible()) continue;
            if (widget.contains(x, y)) return widget;
        }
        return null;
    }

    private static boolean usable(GrugOwnedScreen screen, GrugWidget widget) {
        return screen == openScreen && widget.visible() && widget.active();
    }

    // --- The export calls ---------------------------------------------------------------------

    /**
     * Whether a call on {@code exportFnId} can be made now: the script has its entity and the file
     * exports the function. A script whose member scope is still running has no entity yet, and a
     * screen it opens then is drawn but its calls cannot reach the file.
     */
    static boolean canAsk(GrugOverlayScripts.Script script, long exportFnId) {
        return script.entityHandle != 0 && exportFnId != Grug.INVALID_GRUG_EXPORT_FN_ID;
    }

    private static void askWidgetChanged(GrugOwnedScreen screen, GrugWidget widget) {
        GrugOverlayScripts.Script script = screen.script();
        if (!canAsk(script, script.screenWidgetChangedFnId)) return;
        GrugStateLock.lock();
        try {
            GrugScreenWidgetContext context = new GrugScreenWidgetContext(screen, widget);
            GrugScreenWidgetContext enclosingContext = widgetContext;
            widgetContext = context;
            try {
                calls.callContext(
                        script.entityHandle,
                        script.screenWidgetChangedFnId,
                        GrugEntityType.ScreenWidgetContext,
                        context);
            } finally {
                widgetContext = enclosingContext;
            }
        } finally {
            GrugStateLock.unlock();
        }
    }

    private static void askClosed(GrugOverlayScripts.Script script) {
        if (!canAsk(script, script.screenClosedFnId)) return;
        GrugStateLock.lock();
        try {
            calls.callNoArgs(script.entityHandle, script.screenClosedFnId);
        } finally {
            GrugStateLock.unlock();
        }
    }

    private static void askRow(GrugOwnedScreen screen, GrugWidget widget, int index) {
        GrugOverlayScripts.Script script = screen.script();
        if (!canAsk(script, script.screenListRowFnId)) return;
        GrugListRowResponse response = new GrugListRowResponse(index, widget);
        long responseId =
                GrugEntities.addEntity(GrugEntityType.ListRowResponse, response, frameRowRoots);
        calls.callWithId(script.entityHandle, script.screenListRowFnId, responseId);
        if (response.finish()) {
            widget.addFrameRow(index, response.answeredText(), response.answeredRgb());
        }
    }

    // --- What a unit test drives the lifecycle through ----------------------------------------

    /** Replaces what the exports are called through, and answers the one in use. */
    static Calls useCalls(Calls replacement) {
        Calls previous = calls;
        calls = replacement;
        return previous;
    }

    /**
     * Forgets every screen and every script bound to one, and points the class back at the state.
     */
    static void resetForTest() {
        screens.clear();
        openScreen = null;
        initContext = null;
        widgetContext = null;
        creating = null;
        GrugEntities.clearRoots(frameRowRoots);
        calls = NATIVE;
    }
}
