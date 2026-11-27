package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.grug.minecraft.core.GrugCore;
import net.grug.minecraft.core.ModLoaderAdapter;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

/**
 * The screen model's lifecycle and the calls it makes into a mod's script, which no game test can
 * observe from Java: a capture shows what a screen drew, not which export was asked or in what
 * order. The exports are counted through {@link GrugOwnedScreens#useCalls}, and the loader is a
 * proxy that records what it was told to show.
 */
class GrugOwnedScreensTest {

    /** What one export call was. */
    private static final class Asked {
        final long entityHandle;
        final long exportFnId;
        final Object context;
        final long argumentId;

        Asked(long entityHandle, long exportFnId, Object context, long argumentId) {
            this.entityHandle = entityHandle;
            this.exportFnId = exportFnId;
            this.context = context;
            this.argumentId = argumentId;
        }
    }

    /** Runs each export's work inside the call, the way the VM would run the script. */
    private static final class Recorder implements GrugOwnedScreens.Calls {
        final List<Asked> asked = new ArrayList<>();
        Runnable onInit;
        Runnable onClosed;
        Runnable onWidgetChanged;
        Runnable onListRow;

        @Override
        public boolean callContext(
                long entityHandle, long exportFnId, GrugEntityType contextType, Object context) {
            asked.add(new Asked(entityHandle, exportFnId, context, 0));
            if (contextType == GrugEntityType.ScreenInitContext) {
                if (onInit != null) onInit.run();
            } else if (contextType == GrugEntityType.ScreenWidgetContext) {
                if (onWidgetChanged != null) onWidgetChanged.run();
            }
            return true;
        }

        @Override
        public boolean callWithId(long entityHandle, long exportFnId, long argumentId) {
            asked.add(new Asked(entityHandle, exportFnId, null, argumentId));
            if (onListRow != null) onListRow.run();
            return true;
        }

        @Override
        public boolean callNoArgs(long entityHandle, long exportFnId) {
            asked.add(new Asked(entityHandle, exportFnId, null, 0));
            if (onClosed != null) onClosed.run();
            return true;
        }

        /** The last call to {@code exportFnId}, or null when it was never asked. */
        Asked last(long exportFnId) {
            for (int at = asked.size() - 1; at >= 0; at--) {
                if (asked.get(at).exportFnId == exportFnId) return asked.get(at);
            }
            return null;
        }

        int count(long exportFnId) {
            int count = 0;
            for (Asked call : asked) {
                if (call.exportFnId == exportFnId) count++;
            }
            return count;
        }
    }

    /** The loader, with only the calls the model makes. */
    private static final class Loader {
        boolean supported = true;
        Object level = new Object();
        final List<GrugOwnedScreen> shown = new ArrayList<>();
        final List<GrugOwnedScreen> hidden = new ArrayList<>();

        ModLoaderAdapter adapter() {
            return (ModLoaderAdapter)
                    Proxy.newProxyInstance(
                            ModLoaderAdapter.class.getClassLoader(),
                            new Class<?>[] {ModLoaderAdapter.class},
                            (proxy, method, args) -> {
                                switch (method.getName()) {
                                    case "ownedScreensSupported":
                                        return supported;
                                    case "clientLevel":
                                        return level;
                                    case "showOwnedScreen":
                                        shown.add((GrugOwnedScreen) args[0]);
                                        return null;
                                    case "hideOwnedScreen":
                                        hidden.add((GrugOwnedScreen) args[0]);
                                        return null;
                                    case "logError":
                                        return null;
                                    default:
                                        throw new AssertionError(
                                                "unexpected adapter call " + method.getName());
                                }
                            });
        }
    }

    private Object savedAdapter;
    private final Loader loader = new Loader();
    private final Recorder recorder = new Recorder();
    private GrugOverlayScripts.Script script;

    @BeforeEach
    void installTheLoaderAndACreation() throws Exception {
        Field field = GrugCore.class.getDeclaredField("adapter");
        field.setAccessible(true);
        savedAdapter = field.get(null);
        field.set(null, loader.adapter());
        GrugTestState.testRunActive = false;
        GrugErrors.takeHostFunctionException();
        synchronized (GrugErrors.runtimeErrorQueue) {
            GrugErrors.runtimeErrorQueue.clear();
        }
        GrugOwnedScreens.useCalls(recorder);
        script = newScript(55);
    }

    @AfterEach
    void dropWhatTheTestMade() throws Exception {
        GrugOwnedScreens.resetForTest();
        GrugEntities.entityData.clear();
        GrugEntities.retainedFnEntities.clear();
        GrugEntities.fnEntities = GrugEntities.retainedFnEntities;
        Field field = GrugCore.class.getDeclaredField("adapter");
        field.setAccessible(true);
        field.set(null, savedAdapter);
        GrugTestState.testRunActive = false;
        GrugErrors.takeHostFunctionException();
        synchronized (GrugErrors.runtimeErrorQueue) {
            GrugErrors.runtimeErrorQueue.clear();
        }
    }

    /**
     * A script with the eight screen exports numbered from 1, which the model only passes back to
     * the recorder. A name in {@code invalid} is exported as an invalid id, which is how a script
     * without one of the screen functions is made.
     */
    private static GrugOverlayScripts.Script newScript(long handle, String... invalid) {
        List<String> missing = java.util.Arrays.asList(invalid);
        GrugOverlayScripts.Entities entities =
                new GrugOverlayScripts.Entities() {
                    private long next = 1;

                    @Override
                    public long create(long fileId, List<GrugObject> owner) {
                        return handle;
                    }

                    @Override
                    public void destroy(long entityHandle) {}

                    @Override
                    public long exportFnId(String entityType, String fnName) {
                        if (missing.contains(fnName)) return Grug.INVALID_GRUG_EXPORT_FN_ID;
                        return next++;
                    }
                };
        GrugOverlayScripts.Script script =
                new GrugOverlayScripts.Script("cov_overlay", handle, new ArrayList<>(), "cov");
        script.lookUpExports(entities);
        return script;
    }

    private GrugOwnedScreen createScreen() {
        GrugOwnedScreens.beginScriptCreation(script);
        GrugOwnedScreens.Created created = GrugOwnedScreens.newScreen(loader.adapter());
        GrugOwnedScreens.endScriptCreation();
        assertNull(created.problem, "the test's own creation succeeds");
        return created.screen;
    }

    private GrugOwnedScreen openScreen() {
        GrugOwnedScreen screen = createScreen();
        assertNull(GrugOwnedScreens.open(loader.adapter(), screen));
        return screen;
    }

    private void init(GrugOwnedScreen screen, double width, double height) {
        GrugOwnedScreens.initScreen(screen, width, height);
    }

    private GrugWidget declareButton(GrugOwnedScreen screen, String name) {
        GrugOwnedScreen.Declared declared = screen.declareButton(name, "label", 1, 2, 30, 10);
        assertNull(declared.problem);
        return declared.widget;
    }

    private GrugWidget declareList(GrugOwnedScreen screen, String name) {
        GrugOwnedScreen.Declared declared = screen.declareList(name, 0, 0, 100, 50, 10);
        assertNull(declared.problem);
        return declared.widget;
    }

    // --- Creation -----------------------------------------------------------------------------

    @Test
    void aScreenIsRefusedOnALoaderWithoutScreens() {
        loader.supported = false;
        GrugOwnedScreens.beginScriptCreation(script);
        GrugOwnedScreens.Created created = GrugOwnedScreens.newScreen(loader.adapter());
        GrugOwnedScreens.endScriptCreation();
        assertNull(created.screen);
        assertTrue(created.problem.contains("no mod-owned screens"));
    }

    @Test
    void aScreenIsRefusedOutsideAnOverlayScriptsCreation() {
        GrugOwnedScreens.Created created = GrugOwnedScreens.newScreen(loader.adapter());
        assertNull(created.screen);
        assertTrue(created.problem.contains("member scope of an overlay script"));
    }

    @Test
    void aCreatedScreenIsRootedInTheScriptThatMadeIt() {
        GrugOwnedScreen screen = createScreen();
        assertSame(script, screen.script());
        assertSame(screen, GrugEntities.entityData.get(screen.entityId()).object);
        assertTrue(script.owner.contains(GrugEntities.entityData.get(screen.entityId())));
    }

    // --- Opening and closing ------------------------------------------------------------------

    @Test
    void openingIsRefusedWithoutAWorld() {
        loader.level = null;
        GrugOwnedScreen screen = createScreen();
        String problem = GrugOwnedScreens.open(loader.adapter(), screen);
        assertNotNull(problem);
        assertTrue(problem.contains("no world is loaded"));
        assertTrue(loader.shown.isEmpty());
        assertNull(GrugOwnedScreens.openScreen());
    }

    @Test
    void openingIsRefusedOnALoaderWithoutScreens() {
        GrugOwnedScreen screen = createScreen();
        loader.supported = false;
        String problem = GrugOwnedScreens.open(loader.adapter(), screen);
        assertNotNull(problem);
        assertTrue(problem.contains("no mod-owned screens"));
    }

    @Test
    void openingShowsTheScreenAndAsksItsInit() {
        GrugOwnedScreen screen = openScreen();
        assertEquals(1, loader.shown.size());
        assertSame(screen, loader.shown.get(0));
        assertSame(screen, GrugOwnedScreens.openScreen());

        init(screen, 320, 240);
        Asked call = recorder.last(script.screenInitFnId);
        assertNotNull(call);
        assertEquals(55, call.entityHandle);
        GrugScreenInitContext context = (GrugScreenInitContext) call.context;
        assertEquals(320.0, context.width());
        assertEquals(240.0, context.height());
        assertSame(screen, context.screen());
    }

    @Test
    void openingAnotherScreenAsksTheOpenOnesClosedFirst() {
        GrugOwnedScreen first = openScreen();
        init(first, 320, 240);
        GrugOwnedScreen second = createScreen();
        assertNull(GrugOwnedScreens.open(loader.adapter(), second));

        assertFalse(first.open());
        assertTrue(second.open());
        assertEquals(1, recorder.count(script.screenClosedFnId));
        assertEquals(1, loader.hidden.size());
        assertSame(first, loader.hidden.get(0));
    }

    @Test
    void openingTheOpenScreenAgainClosesItFirst() {
        GrugOwnedScreen screen = openScreen();
        assertNull(GrugOwnedScreens.open(loader.adapter(), screen));
        assertEquals(1, recorder.count(script.screenClosedFnId));
        assertTrue(screen.open());
        assertSame(screen, GrugOwnedScreens.openScreen());
    }

    @Test
    void aClosedHandlerThatOpensAnotherScreenIsClosedToo() {
        GrugOwnedScreen first = openScreen();
        GrugOwnedScreen second = createScreen();
        recorder.onClosed =
                () -> {
                    recorder.onClosed = null;
                    if (GrugOwnedScreens.openScreen() == null) {
                        GrugOwnedScreens.open(loader.adapter(), second);
                    }
                };
        GrugOwnedScreen third = createScreen();
        assertNull(GrugOwnedScreens.open(loader.adapter(), third));
        assertFalse(first.open());
        assertFalse(second.open());
        assertTrue(third.open());
        assertEquals(2, recorder.count(script.screenClosedFnId));
    }

    @Test
    void closingAScreenThatIsNotOpenIsAnErr() {
        GrugOwnedScreen screen = createScreen();
        String problem = GrugOwnedScreens.close(loader.adapter(), screen);
        assertNotNull(problem);
        assertTrue(problem.contains("not the one that is open"));
        assertEquals(0, recorder.count(script.screenClosedFnId));
    }

    @Test
    void closingAsksClosedAndHidesTheScreen() {
        GrugOwnedScreen screen = openScreen();
        assertNull(GrugOwnedScreens.close(loader.adapter(), screen));
        assertFalse(screen.open());
        assertNull(GrugOwnedScreens.openScreen());
        assertEquals(1, recorder.count(script.screenClosedFnId));
        assertSame(screen, loader.hidden.get(0));
    }

    @Test
    void theGameTakingAScreenAwayAsksClosedOnce() {
        GrugOwnedScreen screen = openScreen();
        GrugOwnedScreens.gameRemoved(screen);
        assertFalse(screen.open());
        assertNull(GrugOwnedScreens.openScreen());
        assertEquals(1, recorder.count(script.screenClosedFnId));
        assertTrue(loader.hidden.isEmpty(), "the game already hid it");

        GrugOwnedScreens.gameRemoved(screen);
        assertEquals(1, recorder.count(script.screenClosedFnId));
    }

    @Test
    void leavingTheWorldClosesTheOpenScreen() {
        GrugOwnedScreen screen = openScreen();
        GrugOwnedScreens.worldUnloaded();
        assertFalse(screen.open());
        assertNull(GrugOwnedScreens.openScreen());
        assertEquals(1, recorder.count(script.screenClosedFnId));

        GrugOwnedScreens.worldUnloaded();
        assertEquals(1, recorder.count(script.screenClosedFnId));
    }

    @Test
    void droppingAScriptDropsItsScreensAndClosesTheOpenOne() {
        GrugOwnedScreen open = openScreen();
        GrugOwnedScreen other = createScreen();
        GrugOwnedScreens.scriptDropped(script);
        assertFalse(open.open());
        assertEquals(1, recorder.count(script.screenClosedFnId));
        assertNull(GrugEntities.entityData.get(open.entityId()));
        assertNull(GrugEntities.entityData.get(other.entityId()));
        assertNull(GrugOwnedScreens.openScreen());
    }

    // --- Declaring the widgets ----------------------------------------------------------------

    @Test
    void aWidgetIsDeclaredOnlyDuringInit() {
        GrugOwnedScreen screen = openScreen();
        GrugOwnedScreen.Declared refused = screen.declareButton("button", "label", 0, 0, 10, 10);
        assertNull(refused.widget);
        assertTrue(refused.problem.contains("screen_init"));
    }

    @Test
    void aDeclarationIsRefusedOnAScreenThatIsNotInitializing() {
        GrugOwnedScreen screen = openScreen();
        GrugOwnedScreen other = createScreen();
        recorder.onInit = () -> assertNotNull(screen.declareButton("x", "x", 0, 0, 1, 1).problem);
        GrugOwnedScreens.initScreen(other, 100, 100);
    }

    @Test
    void aLaterInitAnswersTheSameWidgetAndMovesIt() {
        GrugOwnedScreen screen = openScreen();
        GrugWidget[] first = new GrugWidget[1];
        recorder.onInit = () -> first[0] = declareButton(screen, "button");
        init(screen, 320, 240);
        recorder.onInit = () -> assertSame(first[0], declareButton(screen, "button"));
        init(screen, 400, 300);
        assertSame(first[0], screen.widget("button"));
        assertEquals(30.0, first[0].width());
        assertEquals(1, first[0].x());
    }

    @Test
    void theSameNameTwiceInOneInitIsAnErrNamingItsKind() {
        GrugOwnedScreen screen = openScreen();
        recorder.onInit =
                () -> {
                    declareButton(screen, "button");
                    GrugOwnedScreen.Declared again =
                            screen.declareButton("button", "x", 0, 0, 1, 1);
                    assertNull(again.widget);
                    assertTrue(again.problem.contains("already declared as a button"));
                };
        init(screen, 320, 240);
    }

    @Test
    void anotherKindUnderTheSameNameIsAnErrNamingBothKinds() {
        GrugOwnedScreen screen = openScreen();
        recorder.onInit = () -> declareButton(screen, "widget");
        init(screen, 320, 240);
        recorder.onInit =
                () -> {
                    GrugOwnedScreen.Declared refused =
                            screen.declareList("widget", 0, 0, 10, 10, 10);
                    assertNull(refused.widget);
                    assertTrue(refused.problem.contains("button"));
                    assertTrue(refused.problem.contains("list"));
                };
        init(screen, 320, 240);
    }

    @Test
    void everyKindCarriesTheValueItsDeclarationGave() {
        GrugOwnedScreen screen = openScreen();
        recorder.onInit =
                () -> {
                    GrugWidget checkbox =
                            screen.declareCheckbox("box", "on", true, 1, 2, 3, 4).widget;
                    assertTrue(checkbox.checked());
                    assertEquals("on", checkbox.text());

                    GrugWidget slider =
                            screen.declareSlider("slider", "level", 0, 10, 3.5, 1, 2, 100, 20)
                                    .widget;
                    assertEquals(3.5, slider.value());
                    assertEquals("level: 3.5", slider.sliderLabel());

                    GrugWidget field = screen.declareTextField("field", "abc", 1, 2, 30, 12).widget;
                    assertEquals("abc", field.text());
                    assertEquals(1, field.textRevision());

                    GrugWidget list = screen.declareList("list", 0, 0, 100, 50, 10).widget;
                    assertEquals(10.0, list.rowHeight());
                    assertEquals(0, list.rows());

                    GrugWidget moved = screen.declareButton("moved", "x", 1, 2, 3, 4).widget;
                    assertEquals("moved", moved.name());
                    assertEquals(1.0, moved.x());
                    assertEquals(2.0, moved.y());
                    assertEquals(3.0, moved.width());
                    assertEquals(4.0, moved.height());
                    assertEquals(0.0, slider.min());
                    assertEquals(10.0, slider.max());
                };
        init(screen, 320, 240);
    }

    @Test
    void anInfiniteRangeIsAnErr() {
        GrugOwnedScreen screen = openScreen();
        recorder.onInit =
                () ->
                        assertNotNull(
                                screen.declareSlider(
                                                "slider",
                                                "x",
                                                0,
                                                Double.POSITIVE_INFINITY,
                                                0,
                                                0,
                                                0,
                                                1,
                                                1)
                                        .problem);
        init(screen, 320, 240);
    }

    @Test
    void aMaxThatIsNotAboveTheMinIsAnErr() {
        GrugOwnedScreen screen = openScreen();
        recorder.onInit =
                () ->
                        assertNotNull(
                                screen.declareSlider("slider", "x", 5, 5, 0, 0, 0, 1, 1).problem);
        init(screen, 320, 240);
    }

    // --- The player using a widget ------------------------------------------------------------

    @Test
    void aButtonPressAsksWidgetChangedUnlessTheWidgetIsNotUsable() {
        GrugOwnedScreen screen = openScreen();
        GrugWidget[] button = new GrugWidget[1];
        recorder.onInit = () -> button[0] = declareButton(screen, "button");
        init(screen, 320, 240);

        GrugOwnedScreens.buttonPressed(screen, button[0]);
        assertEquals(1, recorder.count(script.screenWidgetChangedFnId));
        Asked call = recorder.last(script.screenWidgetChangedFnId);
        GrugScreenWidgetContext context = (GrugScreenWidgetContext) call.context;
        assertSame(button[0], context.widget());
        assertSame(screen, context.screen());

        button[0].setActive(false);
        GrugOwnedScreens.buttonPressed(screen, button[0]);
        assertEquals(1, recorder.count(script.screenWidgetChangedFnId));

        button[0].setActive(true);
        button[0].setVisible(false);
        GrugOwnedScreens.buttonPressed(screen, button[0]);
        assertEquals(1, recorder.count(script.screenWidgetChangedFnId));
    }

    @Test
    void aPressOnANonOpenScreensWidgetAsksNothing() {
        GrugOwnedScreen screen = openScreen();
        GrugWidget[] button = new GrugWidget[1];
        recorder.onInit = () -> button[0] = declareButton(screen, "button");
        init(screen, 320, 240);
        GrugOwnedScreens.gameRemoved(screen);
        GrugOwnedScreens.buttonPressed(screen, button[0]);
        assertEquals(0, recorder.count(script.screenWidgetChangedFnId));
    }

    @Test
    void tickingACheckboxAsksWidgetChangedOnlyWhenTheValueMoved() {
        GrugOwnedScreen screen = openScreen();
        GrugWidget[] box = new GrugWidget[1];
        recorder.onInit =
                () -> box[0] = screen.declareCheckbox("box", "x", false, 0, 0, 1, 1).widget;
        init(screen, 320, 240);

        GrugOwnedScreens.checkboxChanged(screen, box[0], true);
        assertTrue(box[0].checked());
        assertEquals(1, recorder.count(script.screenWidgetChangedFnId));
        GrugOwnedScreens.checkboxChanged(screen, box[0], true);
        assertEquals(1, recorder.count(script.screenWidgetChangedFnId));
    }

    @Test
    void draggingASliderRecordsTheValueAndAsksOnlyWhenItMoved() {
        GrugOwnedScreen screen = openScreen();
        GrugWidget[] slider = new GrugWidget[1];
        recorder.onInit =
                () -> slider[0] = screen.declareSlider("s", "x", 0, 10, 0, 0, 0, 100, 20).widget;
        init(screen, 320, 240);

        GrugOwnedScreens.sliderMoved(screen, slider[0], 0.5);
        assertEquals(5.0, slider[0].value());
        assertEquals(0.5, slider[0].sliderFraction());
        assertEquals(1, recorder.count(script.screenWidgetChangedFnId));
        GrugOwnedScreens.sliderMoved(screen, slider[0], 0.5);
        assertEquals(1, recorder.count(script.screenWidgetChangedFnId));
    }

    @Test
    void clickingARowSelectsItAndAsksOnlyWhenTheSelectionMoved() {
        GrugOwnedScreen screen = openScreen();
        GrugWidget[] list = new GrugWidget[1];
        recorder.onInit =
                () -> {
                    list[0] = declareList(screen, "list");
                    list[0].applyRows(3);
                };
        init(screen, 320, 240);

        GrugOwnedScreens.listRowClicked(screen, list[0], 1);
        assertEquals(1, list[0].selected());
        assertEquals(1, recorder.count(script.screenWidgetChangedFnId));
        GrugOwnedScreens.listRowClicked(screen, list[0], 1);
        assertEquals(1, recorder.count(script.screenWidgetChangedFnId));
        GrugOwnedScreens.listRowClicked(screen, list[0], 9);
        assertEquals(1, list[0].selected());
    }

    @Test
    void editingATextFieldAsksWidgetChangedOnlyWhenTheTextMoved() {
        GrugOwnedScreen screen = openScreen();
        GrugWidget[] field = new GrugWidget[1];
        recorder.onInit = () -> field[0] = screen.declareTextField("f", "a", 0, 0, 10, 10).widget;
        init(screen, 320, 240);

        GrugOwnedScreens.textEdited(screen, field[0], "ab");
        assertEquals("ab", field[0].text());
        assertEquals(2, field[0].textRevision());
        assertEquals(1, recorder.count(script.screenWidgetChangedFnId));
        GrugOwnedScreens.textEdited(screen, field[0], "ab");
        assertEquals(1, recorder.count(script.screenWidgetChangedFnId));
        GrugOwnedScreens.textEdited(screen, field[0], null);
        assertEquals("", field[0].text());
    }

    @Test
    void aModelsOwnWriteNeverAsksWidgetChanged() {
        GrugOwnedScreen screen = openScreen();
        GrugWidget[] widgets = new GrugWidget[2];
        recorder.onInit =
                () -> {
                    widgets[0] = screen.declareCheckbox("box", "x", false, 0, 0, 1, 1).widget;
                    widgets[1] = screen.declareList("list", 0, 0, 10, 10, 10).widget;
                };
        init(screen, 320, 240);

        widgets[0].setChecked(true);
        widgets[1].applyRows(2);
        widgets[1].select(1);
        widgets[0].setText("new");
        assertEquals(0, recorder.count(script.screenWidgetChangedFnId));
    }

    @Test
    void theListUnderThePointIsTheVisibleOne() {
        GrugOwnedScreen screen = openScreen();
        GrugWidget[] lists = new GrugWidget[2];
        recorder.onInit =
                () -> {
                    screen.declareButton("before", "x", 0, 0, 10, 10);
                    lists[0] = screen.declareList("below", 0, 0, 100, 20, 10).widget;
                    lists[1] = screen.declareList("above", 0, 0, 100, 20, 10).widget;
                };
        init(screen, 320, 240);

        assertSame(lists[0], GrugOwnedScreens.listAt(screen, 5, 5));
        lists[0].setVisible(false);
        assertSame(lists[1], GrugOwnedScreens.listAt(screen, 5, 5));
        assertNull(GrugOwnedScreens.listAt(screen, 500, 500));
    }

    @Test
    void theWheelScrollsOnlyTheUsableListUnderThePoint() {
        GrugOwnedScreen screen = openScreen();
        GrugWidget[] lists = new GrugWidget[2];
        recorder.onInit =
                () -> {
                    screen.declareButton("before", "x", 0, 0, 10, 10);
                    lists[0] = screen.declareList("one", 0, 0, 100, 20, 10).widget;
                    lists[1] = screen.declareList("two", 0, 0, 100, 20, 10).widget;
                    lists[0].applyRows(10);
                    lists[1].applyRows(10);
                };
        init(screen, 320, 240);

        GrugWidget list = GrugOwnedScreens.listAt(screen, 5, 5);
        assertTrue(GrugOwnedScreens.scrollList(screen, list, -1.0));
        assertEquals(10.0, list.scroll());
        GrugOwnedScreens.gameRemoved(screen);
        assertFalse(GrugOwnedScreens.scrollList(screen, list, -1.0));
    }

    // --- The list's rows ----------------------------------------------------------------------

    @Test
    void theFrameAsksOnlyTheRowsTheViewportShows() {
        GrugOwnedScreen screen = openScreen();
        GrugWidget[] list = new GrugWidget[1];
        recorder.onInit =
                () -> {
                    list[0] = screen.declareList("list", 0, 0, 100, 30, 10).widget;
                    list[0].applyRows(5);
                };
        init(screen, 320, 240);
        recorder.asked.clear();
        recorder.onListRow =
                () -> {
                    Asked call = recorder.asked.get(recorder.asked.size() - 1);
                    GrugListRowResponse response =
                            (GrugListRowResponse)
                                    GrugEntities.entityData.get(call.argumentId).object;
                    HostFunctions.ListRowResponse_set_text(
                            call.argumentId, "row " + response.index());
                };
        GrugOwnedScreens.prepareFrame(screen);

        assertEquals(3, recorder.asked.size(), "rows 0, 1 and 2 fit in 30 pixels");
        assertEquals(3, list[0].frameRows().size());
        for (int index = 0; index < 3; index++) {
            assertEquals(index, list[0].frameRows().get(index).index());
            assertEquals("row " + index, list[0].frameRows().get(index).text());
        }
    }

    @Test
    void aRowResponseAnswersItsIndexAndWidget() {
        GrugOwnedScreen screen = openScreen();
        GrugWidget[] list = new GrugWidget[1];
        recorder.onInit =
                () -> {
                    list[0] = screen.declareList("list", 0, 0, 100, 30, 10).widget;
                    list[0].applyRows(2);
                };
        init(screen, 320, 240);
        long[] seen = new long[2];
        recorder.onListRow =
                () -> {
                    Asked call = recorder.asked.get(recorder.asked.size() - 1);
                    seen[0] = (long) HostFunctions.ListRowResponse_index(call.argumentId);
                    seen[1] = HostFunctions.ListRowResponse_widget(call.argumentId);
                    HostFunctions.ListRowResponse_set_text(call.argumentId, "x");
                    HostFunctions.ListRowResponse_set_text_colour(
                            call.argumentId,
                            GrugEntities.addEntity(GrugEntityType.Color, new Color(255, 0, 0)));
                };
        GrugOwnedScreens.prepareFrame(screen);
        assertEquals(1, seen[0]);
        assertSame(list[0], GrugEntities.entityData.get(seen[1]).object);
        assertEquals(0xFF0000, list[0].frameRows().get(1).rgb());
    }

    @Test
    void aRowThatAsksForNoColourDrawsItsColouredDefault() {
        GrugOwnedScreen screen = openScreen();
        GrugWidget[] list = new GrugWidget[1];
        recorder.onInit =
                () -> {
                    list[0] = screen.declareList("list", 0, 0, 100, 30, 10).widget;
                    list[0].applyRows(1);
                };
        init(screen, 320, 240);
        recorder.onListRow =
                () -> {
                    Asked call = recorder.asked.get(recorder.asked.size() - 1);
                    HostFunctions.ListRowResponse_set_text(call.argumentId, "x");
                };
        GrugOwnedScreens.prepareFrame(screen);
        assertEquals(-1, list[0].frameRows().get(0).rgb());
    }

    @Test
    void anUnansweredRowIsAnErr() {
        GrugOwnedScreen screen = openScreen();
        GrugWidget[] list = new GrugWidget[1];
        recorder.onInit =
                () -> {
                    list[0] = screen.declareList("list", 0, 0, 100, 30, 10).widget;
                    list[0].applyRows(1);
                };
        init(screen, 320, 240);
        GrugTestState.testRunActive = true;
        GrugOwnedScreens.prepareFrame(screen);
        String reported = GrugErrors.takeHostFunctionException();
        assertNotNull(reported);
        assertTrue(reported.contains("returned without answering"));
        assertTrue(list[0].frameRows().isEmpty());
    }

    @Test
    void aHiddenListAsksForNoRows() {
        GrugOwnedScreen screen = openScreen();
        GrugWidget[] list = new GrugWidget[1];
        recorder.onInit =
                () -> {
                    list[0] = screen.declareList("list", 0, 0, 100, 30, 10).widget;
                    list[0].applyRows(3);
                    list[0].setVisible(false);
                };
        init(screen, 320, 240);
        recorder.asked.clear();
        GrugOwnedScreens.prepareFrame(screen);
        assertTrue(recorder.asked.isEmpty());
    }

    @Test
    void aFrameOnAScreenThatIsNotOpenAsksNothing() {
        GrugOwnedScreen screen = createScreen();
        recorder.asked.clear();
        GrugOwnedScreens.initScreen(screen, 320, 240);
        GrugOwnedScreens.prepareFrame(screen);
        assertTrue(recorder.asked.isEmpty());
        assertEquals(GrugOverlayGeometry.EMPTY, GrugOwnedScreens.renderScreen(screen, 1, 1, 0, 0));
        assertFalse(GrugOwnedScreens.clickScreen(screen, 1, 1, 0, 0, 0, false));
        assertFalse(GrugOwnedScreens.keyScreen(screen, 1, 0, false, false, false));
        assertFalse(GrugOwnedScreens.scrollScreen(screen, 1, 1, 0, 0, 1));
    }

    @Test
    void anOpenScreensFrameReachesItsOwnExports() {
        GrugOverlayScripts.Script noEntity = newScript(0);
        GrugOwnedScreens.beginScriptCreation(noEntity);
        GrugOwnedScreen screen = GrugOwnedScreens.newScreen(loader.adapter()).screen;
        GrugOwnedScreens.endScriptCreation();
        assertNull(GrugOwnedScreens.open(loader.adapter(), screen));
        recorder.asked.clear();
        // The script has no entity handle yet, so its pass reaches no export and answers the
        // empty result rather than failing.
        GrugOverlayGeometry drawn = GrugOwnedScreens.renderScreen(screen, 1, 1, 0, 0);
        assertTrue(drawn.items().isEmpty());
        assertFalse(GrugOwnedScreens.clickScreen(screen, 1, 1, 0, 0, 0, false));
        assertFalse(GrugOwnedScreens.keyScreen(screen, 1, 0, false, false, false));
        assertFalse(GrugOwnedScreens.scrollScreen(screen, 1, 1, 0, 0, 1));
        assertTrue(recorder.asked.isEmpty(), "the recorder is not how a pass calls an export");
    }

    @Test
    void aPlayerActionOnAScriptWithNoEntityIsNotAsked() {
        GrugOwnedScreen screen = openScreen();
        GrugWidget[] button = new GrugWidget[1];
        recorder.onInit = () -> button[0] = declareButton(screen, "button");
        init(screen, 320, 240);
        recorder.asked.clear();
        // The guard is the window a script is in while its member scope runs, before its entity
        // is made: no widget can be declared then, so the test puts the script back in it.
        script.entityHandle = 0;
        GrugOwnedScreens.buttonPressed(screen, button[0]);
        assertTrue(recorder.asked.isEmpty(), "no export is asked on an entity that was not made");
    }

    @Test
    void aScriptWithoutTheExportsAsksNothing() {
        String[] exports = {
            "screen_init",
            "screen_render",
            "screen_click",
            "screen_key",
            "screen_scroll",
            "screen_widget_changed",
            "screen_list_row",
            "screen_closed"
        };
        GrugOverlayScripts.Script bare = newScript(66, exports);
        GrugOwnedScreens.beginScriptCreation(bare);
        GrugOwnedScreen screen = GrugOwnedScreens.newScreen(loader.adapter()).screen;
        GrugOwnedScreens.endScriptCreation();
        assertNull(GrugOwnedScreens.open(loader.adapter(), screen));
        recorder.asked.clear();
        init(screen, 100, 100);
        GrugOwnedScreens.prepareFrame(screen);
        GrugOwnedScreens.gameRemoved(screen);
        assertTrue(recorder.asked.isEmpty());
    }

    @Test
    void aListRowScriptWithoutItsExportAsksNoRow() {
        GrugOverlayScripts.Script script = newScript(77, "screen_list_row");
        GrugOwnedScreens.beginScriptCreation(script);
        GrugOwnedScreen screen = GrugOwnedScreens.newScreen(loader.adapter()).screen;
        GrugOwnedScreens.endScriptCreation();
        assertNull(GrugOwnedScreens.open(loader.adapter(), screen));
        recorder.onInit =
                () -> {
                    GrugWidget list = declareList(screen, "list");
                    list.applyRows(3);
                };
        init(screen, 320, 240);
        recorder.asked.clear();
        GrugOwnedScreens.prepareFrame(screen);
        assertTrue(recorder.asked.isEmpty(), "an export the script lacks is not asked");
    }

    // --- The model's own decisions, which no game run observes --------------------------------

    @Test
    void aScreenWithoutALoaderIsRefused() {
        GrugOwnedScreens.Created created = GrugOwnedScreens.newScreen(null);
        assertNull(created.screen);
        assertTrue(created.problem.contains("no mod-owned screens"));

        GrugOwnedScreen screen = createScreen();
        assertNotNull(GrugOwnedScreens.open(null, screen));
        assertNull(GrugOwnedScreens.openScreen());
    }

    @Test
    void onlyTheInitsOwnScreenCountsAsInitializing() {
        GrugOwnedScreen screen = openScreen();
        GrugOwnedScreen other = createScreen();
        assertFalse(GrugOwnedScreens.isInitializing(screen));

        boolean[] seen = new boolean[2];
        recorder.onInit =
                () -> {
                    seen[0] = GrugOwnedScreens.isInitializing(screen);
                    seen[1] = GrugOwnedScreens.isInitializing(other);
                };
        GrugOwnedScreens.initScreen(screen, 100, 100);
        assertTrue(seen[0]);
        assertFalse(seen[1]);
    }

    @Test
    void anExportIsAskedOnlyWithAnEntityAndTheFunction() {
        assertTrue(GrugOwnedScreens.canAsk(script, script.screenInitFnId));
        assertFalse(GrugOwnedScreens.canAsk(newScript(0), script.screenInitFnId));
        assertFalse(GrugOwnedScreens.canAsk(script, Grug.INVALID_GRUG_EXPORT_FN_ID));
    }

    @Test
    void aPointIsInsideOnlyAHalfOpenRectangle() {
        GrugOwnedScreen screen = openScreen();
        GrugWidget[] button = new GrugWidget[1];
        recorder.onInit = () -> button[0] = declareButton(screen, "button");
        init(screen, 320, 240);

        assertTrue(button[0].contains(1.0, 2.0), "the top left corner is inside");
        assertTrue(button[0].contains(30.9, 11.9));
        assertFalse(button[0].contains(31.0, 2.0), "the right edge is outside");
        assertFalse(button[0].contains(1.0, 12.0), "the bottom edge is outside");
        assertFalse(button[0].contains(0.9, 2.0));
        assertFalse(button[0].contains(1.0, 1.9));
    }

    @Test
    void everyPlayerCallOnAnUnusableWidgetIsIgnored() {
        GrugOwnedScreen screen = openScreen();
        GrugWidget[] widgets = new GrugWidget[4];
        recorder.onInit =
                () -> {
                    widgets[0] = screen.declareCheckbox("box", "x", false, 0, 0, 1, 1).widget;
                    widgets[1] = screen.declareSlider("slider", "x", 0, 10, 0, 0, 0, 10, 10).widget;
                    widgets[2] = screen.declareList("list", 0, 0, 10, 10, 10).widget;
                    widgets[3] = screen.declareTextField("field", "a", 0, 0, 10, 10).widget;
                    widgets[2].applyRows(3);
                };
        init(screen, 320, 240);

        for (GrugWidget widget : widgets) widget.setActive(false);
        GrugOwnedScreens.checkboxChanged(screen, widgets[0], true);
        GrugOwnedScreens.sliderMoved(screen, widgets[1], 1.0);
        GrugOwnedScreens.listRowClicked(screen, widgets[2], 1);
        GrugOwnedScreens.textEdited(screen, widgets[3], "b");
        assertFalse(GrugOwnedScreens.scrollList(screen, widgets[2], -1.0));

        assertFalse(widgets[0].checked());
        assertEquals(0.0, widgets[1].value());
        assertEquals(-1, widgets[2].selected());
        assertEquals("a", widgets[3].text());
        assertEquals(0, recorder.count(script.screenWidgetChangedFnId));
    }

    @Test
    void aRowNumberOutsideTheListChangesNothing() {
        GrugOwnedScreen screen = openScreen();
        GrugWidget[] list = new GrugWidget[1];
        recorder.onInit =
                () -> {
                    list[0] = screen.declareList("list", 0, 0, 10, 10, 10).widget;
                    list[0].applyRows(2);
                };
        init(screen, 320, 240);

        GrugOwnedScreens.listRowClicked(screen, list[0], -1);
        GrugOwnedScreens.listRowClicked(screen, list[0], 2);
        assertFalse(list[0].select(-1));
        assertFalse(list[0].select(2));
        assertEquals(-1, list[0].selected());
        assertEquals(0, recorder.count(script.screenWidgetChangedFnId));
    }

    @Test
    void aListAtItsEndDoesNotScroll() {
        GrugOwnedScreen screen = openScreen();
        GrugWidget[] list = new GrugWidget[1];
        recorder.onInit =
                () -> {
                    list[0] = screen.declareList("list", 0, 0, 10, 50, 10).widget;
                    list[0].applyRows(1);
                };
        init(screen, 320, 240);
        assertFalse(GrugOwnedScreens.scrollList(screen, list[0], -1.0));
        assertFalse(GrugOwnedScreens.scrollList(screen, list[0], 1.0));
        assertEquals(0.0, list[0].scroll());
    }

    @Test
    void aModelThatWritesNoTextClearsTheField() {
        GrugOwnedScreen screen = openScreen();
        GrugWidget[] field = new GrugWidget[1];
        recorder.onInit =
                () -> field[0] = screen.declareTextField("field", "a", 0, 0, 10, 10).widget;
        init(screen, 320, 240);
        long revision = field[0].textRevision();
        field[0].setText(null);
        assertEquals("", field[0].text());
        assertEquals(revision + 1, field[0].textRevision());
        field[0].setText(null);
        assertEquals(revision + 1, field[0].textRevision(), "the same text leaves the revision");
    }

    @Test
    void theFrameSkipsAListWithNoRows() {
        GrugOwnedScreen screen = openScreen();
        recorder.onInit =
                () -> {
                    screen.declareButton("button", "x", 0, 0, 10, 10);
                    screen.declareList("empty", 0, 0, 100, 30, 10);
                };
        init(screen, 320, 240);
        recorder.asked.clear();
        GrugOwnedScreens.prepareFrame(screen);
        assertTrue(recorder.asked.isEmpty(), "no row is asked for an empty list");
    }

    @Test
    void everyKindRefusesASecondDeclarationOfTheNameInOneInit() {
        GrugOwnedScreen screen = openScreen();
        recorder.onInit =
                () -> {
                    declareButton(screen, "x");
                    assertNotNull(screen.declareCheckbox("x", "x", false, 0, 0, 1, 1).problem);
                    assertNotNull(screen.declareList("x", 0, 0, 1, 1, 10).problem);
                    assertNotNull(screen.declareSlider("x", "x", 0, 10, 0, 0, 0, 1, 1).problem);
                    assertNotNull(screen.declareTextField("x", "x", 0, 0, 1, 1).problem);
                };
        init(screen, 320, 240);
    }

    @Test
    void droppingAScriptLeavesTheScreensOfAnotherScript() {
        GrugOwnedScreen mine = openScreen();
        GrugOverlayScripts.Script other = newScript(88);
        GrugOwnedScreens.beginScriptCreation(other);
        GrugOwnedScreen theirs = GrugOwnedScreens.newScreen(loader.adapter()).screen;
        GrugOwnedScreens.endScriptCreation();

        GrugOwnedScreens.scriptDropped(script);
        assertNull(GrugEntities.entityData.get(mine.entityId()));
        assertSame(theirs, GrugEntities.entityData.get(theirs.entityId()).object);
    }
}
