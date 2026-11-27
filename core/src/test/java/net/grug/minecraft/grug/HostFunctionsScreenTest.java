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
 * The host functions a mod's screen and widgets call, driven from Java so each refusal is measured
 * once: a game test can only reach the paths one player action at a time, and the widget calls'
 * errors are what a port reads when it passes the wrong handle.
 */
class HostFunctionsScreenTest {

    /** The loader, with only what the screen lifecycle asks. */
    private static final class Loader {
        boolean supported = true;
        Object level = new Object();
        final List<GrugOwnedScreen> shown = new ArrayList<>();

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
                                    case "logError":
                                        return null;
                                    default:
                                        throw new AssertionError(
                                                "unexpected adapter call " + method.getName());
                                }
                            });
        }
    }

    /** Runs each export's work inside the call, the way the VM would run the script. */
    private static final class Recorder implements GrugOwnedScreens.Calls {
        Runnable onInit;
        Runnable onWidgetChanged;
        Runnable onRow;
        long lastArgumentId;

        @Override
        public boolean callContext(
                long entityHandle, long exportFnId, GrugEntityType contextType, Object context) {
            if (contextType == GrugEntityType.ScreenInitContext) {
                if (onInit != null) onInit.run();
            } else if (contextType == GrugEntityType.ScreenWidgetContext) {
                if (onWidgetChanged != null) onWidgetChanged.run();
            }
            return true;
        }

        @Override
        public boolean callWithId(long entityHandle, long exportFnId, long argumentId) {
            lastArgumentId = argumentId;
            if (onRow != null) onRow.run();
            return true;
        }

        @Override
        public boolean callNoArgs(long entityHandle, long exportFnId) {
            return true;
        }
    }

    private Object savedAdapter;
    private boolean savedRunActive;
    private final Loader loader = new Loader();
    private final Recorder recorder = new Recorder();
    private GrugOverlayScripts.Script script;
    private GrugOwnedScreen screen;

    @BeforeEach
    void openAScreen() throws Exception {
        Field field = GrugCore.class.getDeclaredField("adapter");
        field.setAccessible(true);
        savedAdapter = field.get(null);
        field.set(null, loader.adapter());
        savedRunActive = GrugTestState.testRunActive;
        GrugTestState.testRunActive = true;
        GrugErrors.takeHostFunctionException();
        synchronized (GrugErrors.runtimeErrorQueue) {
            GrugErrors.runtimeErrorQueue.clear();
        }
        GrugOwnedScreens.useCalls(recorder);
        GrugEntities.entityData.clear();
        GrugEntities.retainedFnEntities.clear();
        GrugEntities.fnEntities = GrugEntities.retainedFnEntities;

        GrugOverlayScripts.Entities entities =
                new GrugOverlayScripts.Entities() {
                    private long next = 1;

                    @Override
                    public long create(long fileId, List<GrugObject> owner) {
                        return 70;
                    }

                    @Override
                    public void destroy(long entityHandle) {}

                    @Override
                    public long exportFnId(String entityType, String fnName) {
                        return next++;
                    }
                };
        script = new GrugOverlayScripts.Script("cov_overlay", 70, new ArrayList<>(), "cov");
        script.lookUpExports(entities);
        GrugOwnedScreens.beginScriptCreation(script);
        screen = GrugOwnedScreens.newScreen(loader.adapter()).screen;
        GrugOwnedScreens.endScriptCreation();
        GrugOwnedScreens.open(loader.adapter(), screen);
    }

    @AfterEach
    void clearState() throws Exception {
        GrugOwnedScreens.resetForTest();
        GrugEntities.entityData.clear();
        GrugEntities.retainedFnEntities.clear();
        GrugEntities.fnEntities = GrugEntities.retainedFnEntities;
        Field field = GrugCore.class.getDeclaredField("adapter");
        field.setAccessible(true);
        field.set(null, savedAdapter);
        GrugTestState.testRunActive = savedRunActive;
        GrugErrors.takeHostFunctionException();
        synchronized (GrugErrors.runtimeErrorQueue) {
            GrugErrors.runtimeErrorQueue.clear();
        }
    }

    private void init() {
        GrugOwnedScreens.initScreen(screen, 320, 240);
    }

    private long button(String name) {
        return HostFunctions.Screen_add_button(screen.entityId(), name, "label", 1, 2, 30, 10);
    }

    private long list(String name) {
        return HostFunctions.Screen_add_list(screen.entityId(), name, 0, 0, 100, 50, 10);
    }

    private String reported() {
        return GrugErrors.takeHostFunctionException();
    }

    /** A refused context is reported to the mod tree, which a Java test reads off the queue. */
    private static String refused() {
        synchronized (GrugErrors.runtimeErrorQueue) {
            if (GrugErrors.runtimeErrorQueue.isEmpty()) return null;
            return GrugErrors.runtimeErrorQueue.poll();
        }
    }

    // --- The screen's own calls -----------------------------------------------------------------

    @Test
    void openingAsksTheInitWithTheScreensSize() {
        long[] seen = new long[1];
        recorder.onInit =
                () -> {
                    GrugScreenInitContext context = GrugOwnedScreens.initContext();
                    long contextId =
                            GrugEntities.addEntity(GrugEntityType.ScreenInitContext, context);
                    assertEquals(320.0, HostFunctions.ScreenInitContext_screen_width(contextId));
                    assertEquals(240.0, HostFunctions.ScreenInitContext_screen_height(contextId));
                    assertSame(context, GrugEntities.entityData.get(contextId).object);
                    seen[0] = contextId;
                };
        init();
        assertTrue(seen[0] != 0, "the init was asked for a context");

        // The context is refused once the call that was passed it has returned.
        assertEquals(0.0, HostFunctions.ScreenInitContext_screen_width(seen[0]));
        assertTrue(refused().contains("only valid while"));
    }

    @Test
    void theWidgetContextNamesTheWidgetThePlayerUsed() {
        long[] seen = new long[1];
        recorder.onInit = () -> seen[0] = button("button");
        init();
        long[] widgetId = new long[1];
        recorder.onWidgetChanged =
                () -> {
                    GrugScreenWidgetContext context = GrugOwnedScreens.widgetContext();
                    long contextId =
                            GrugEntities.addEntity(GrugEntityType.ScreenWidgetContext, context);
                    widgetId[0] = HostFunctions.ScreenWidgetContext_widget(contextId);
                    assertSame(context, GrugEntities.entityData.get(contextId).object);
                };
        GrugOwnedScreens.buttonPressed(screen, screen.widget("button"));
        assertEquals(seen[0], widgetId[0]);
        assertNull(reported());
    }

    @Test
    void aWidgetDeclarationOutsideInitIsAnErr() {
        assertEquals(0, button("button"));
        assertTrue(reported().contains("screen_init"));
    }

    @Test
    void aSecondDeclarationOfTheSameNameInOneInitIsAnErr() {
        recorder.onInit =
                () -> {
                    assertTrue(button("button") != 0);
                    assertEquals(0, button("button"));
                    assertTrue(reported().contains("already declared as a button"));
                };
        init();
    }

    @Test
    void aWidgetThatIsNotARectangleIsAnErr() {
        recorder.onInit =
                () -> {
                    assertEquals(
                            0,
                            HostFunctions.Screen_add_button(
                                    screen.entityId(), "button", "label", Double.NaN, 0, 1, 1));
                    assertTrue(reported().contains("finite"));
                };
        init();
    }

    @Test
    void aListRowHeightThatCannotDrawIsAnErr() {
        recorder.onInit =
                () -> {
                    assertEquals(
                            0,
                            HostFunctions.Screen_add_list(
                                    screen.entityId(), "list", 0, 0, 10, 10, 0));
                    assertTrue(reported().contains("positive finite"));
                    assertEquals(
                            0,
                            HostFunctions.Screen_add_list(
                                    screen.entityId(), "list", 0, 0, 10, 10, Double.NaN));
                    assertTrue(reported().contains("positive finite"));
                };
        init();
    }

    @Test
    void aSliderWhoseRangeCannotBeWalkedIsAnErr() {
        recorder.onInit =
                () -> {
                    assertEquals(
                            0,
                            HostFunctions.Screen_add_slider(
                                    screen.entityId(), "slider", "x", 0, 0, 0, 0, 0, 1, 1));
                    assertTrue(reported().contains("greater than the min"));
                    assertEquals(
                            0,
                            HostFunctions.Screen_add_slider(
                                    screen.entityId(),
                                    "other",
                                    "x",
                                    0,
                                    Double.NEGATIVE_INFINITY,
                                    0,
                                    0,
                                    0,
                                    1,
                                    1));
                    assertTrue(reported().contains("finite"));
                };
        init();
    }

    @Test
    void aScreenWhoseEntityIsGoneIsAnErr() {
        long id = screen.entityId();
        GrugOwnedScreens.scriptDropped(script);
        HostFunctions.Screen_open(id);
        assertTrue(reported().contains("no longer resolves"));
        assertEquals(0, HostFunctions.Screen_add_button(id, "b", "l", 0, 0, 1, 1));
        assertTrue(reported().contains("no longer resolves"));
    }

    @Test
    void openingAScreenWhileNoWorldIsLoadedIsAnErr() {
        loader.level = null;
        long id = screen.entityId();
        GrugOwnedScreens.gameRemoved(screen);
        HostFunctions.Screen_open(id);
        assertTrue(reported().contains("no world is loaded"));
    }

    @Test
    void closingAScreenThatIsNotOpenIsAnErr() {
        long second;
        GrugOwnedScreens.beginScriptCreation(script);
        second = GrugOwnedScreens.newScreen(loader.adapter()).screen.entityId();
        GrugOwnedScreens.endScriptCreation();
        HostFunctions.Screen_close(second);
        assertTrue(reported().contains("not the one that is open"));
    }

    // --- The widget's own calls -----------------------------------------------------------------

    @Test
    void eachReadAnswersTheValueTheDeclarationGave() {
        long[] ids = new long[4];
        recorder.onInit =
                () -> {
                    ids[0] =
                            HostFunctions.Screen_add_checkbox(
                                    screen.entityId(), "box", "on", true, 0, 0, 10, 10);
                    ids[1] =
                            HostFunctions.Screen_add_slider(
                                    screen.entityId(),
                                    "slider",
                                    "level",
                                    0,
                                    10,
                                    2.5,
                                    0,
                                    0,
                                    100,
                                    20);
                    ids[2] =
                            HostFunctions.Screen_add_text_field(
                                    screen.entityId(), "field", "abc", 0, 0, 30, 12);
                    ids[3] = list("list");
                };
        init();

        assertTrue(HostFunctions.Widget_checked(ids[0]));
        assertEquals(2.5, HostFunctions.Widget_value(ids[1]));
        assertEquals("level: 2.5", screen.widget("slider").sliderLabel());
        assertEquals("abc", HostFunctions.Widget_text(ids[2]));
        assertEquals(-1.0, HostFunctions.Widget_selected(ids[3]));
        assertNull(reported());
    }

    @Test
    void aCallTheWidgetsKindDoesNotHaveNamesBothKinds() {
        long[] ids = new long[2];
        recorder.onInit =
                () -> {
                    ids[0] = button("button");
                    ids[1] = list("list");
                };
        init();

        assertFalse(HostFunctions.Widget_checked(ids[0]));
        String box = reported();
        assertNotNull(box);
        assertTrue(box.contains("button"), box);
        assertTrue(box.contains("checkbox"), box);

        assertEquals(0.0, HostFunctions.Widget_value(ids[1]));
        String slider = reported();
        assertNotNull(slider);
        assertTrue(slider.contains("list"), slider);
        assertTrue(slider.contains("slider"), slider);

        assertEquals(-1.0, HostFunctions.Widget_selected(ids[0]));
        assertTrue(reported().contains("button"));
    }

    @Test
    void aListHasNoTextOfItsOwn() {
        long[] id = new long[1];
        recorder.onInit = () -> id[0] = list("list");
        init();
        assertEquals("", HostFunctions.Widget_text(id[0]));
        assertTrue(reported().contains("list has no text of its own"));
    }

    @Test
    void aModsOwnWriteNeverAsksWidgetChanged() {
        long[] id = new long[2];
        recorder.onInit =
                () -> {
                    id[0] =
                            HostFunctions.Screen_add_checkbox(
                                    screen.entityId(), "box", "on", false, 0, 0, 10, 10);
                    id[1] =
                            HostFunctions.Screen_add_text_field(
                                    screen.entityId(), "field", "abc", 0, 0, 30, 12);
                };
        init();
        int[] moved = new int[1];
        recorder.onWidgetChanged = () -> moved[0]++;

        HostFunctions.Widget_set_checked(id[0], true);
        HostFunctions.Widget_set_text(id[1], "abcd");
        HostFunctions.Widget_set_position(id[0], 5, 5);
        HostFunctions.Widget_set_active(id[0], false);
        HostFunctions.Widget_set_visible(id[0], false);

        assertTrue(HostFunctions.Widget_checked(id[0]));
        assertEquals("abcd", HostFunctions.Widget_text(id[1]));
        assertEquals(0, moved[0]);
        assertNull(reported());
    }

    @Test
    void aSelectNeedsAWholeRowTheListHas() {
        long[] id = new long[1];
        recorder.onInit =
                () -> {
                    id[0] = list("list");
                    HostFunctions.Widget_set_rows(id[0], 3);
                };
        init();

        HostFunctions.Widget_select(id[0], 2);
        assertEquals(2.0, HostFunctions.Widget_selected(id[0]));
        assertNull(reported());

        HostFunctions.Widget_select(id[0], 3);
        assertTrue(reported().contains("3 rows"));

        HostFunctions.Widget_select(id[0], 1.5);
        assertTrue(reported().contains("whole number"));

        HostFunctions.Widget_select(id[0], -1);
        assertTrue(reported().contains("whole number"));

        HostFunctions.Widget_set_rows(id[0], 1);
        assertEquals(-1.0, HostFunctions.Widget_selected(id[0]), "the selection is dropped");
    }

    @Test
    void aSelectOnAnEmptyListIsAnErr() {
        long[] id = new long[1];
        recorder.onInit = () -> id[0] = list("list");
        init();
        HostFunctions.Widget_select(id[0], 0);
        assertTrue(reported().contains("no rows"));
    }

    @Test
    void setRowsNeedsAWholeNumber() {
        long[] id = new long[1];
        recorder.onInit = () -> id[0] = list("list");
        init();
        HostFunctions.Widget_set_rows(id[0], 2.5);
        assertTrue(reported().contains("whole number"));
        HostFunctions.Widget_set_rows(id[0], -1);
        assertTrue(reported().contains("whole number"));
        HostFunctions.Widget_set_rows(id[0], 2);
        assertEquals(2, screen.widget("list").rows());
        assertNull(reported());
    }

    @Test
    void aBoxOrASliderCanBeWrittenByTheMod() {
        long[] id = new long[2];
        recorder.onInit =
                () -> {
                    id[0] =
                            HostFunctions.Screen_add_checkbox(
                                    screen.entityId(), "box", "on", false, 0, 0, 10, 10);
                    id[1] =
                            HostFunctions.Screen_add_slider(
                                    screen.entityId(), "slider", "level", 0, 10, 0, 0, 0, 100, 20);
                };
        init();
        HostFunctions.Widget_set_checked(id[0], true);
        HostFunctions.Widget_set_value(id[1], 42.0);
        assertTrue(HostFunctions.Widget_checked(id[0]));
        assertEquals(10.0, HostFunctions.Widget_value(id[1]), "the value is brought into range");
        assertEquals(
                1.0,
                screen.widget("slider").sliderFraction(),
                "the handle is drawn at the end of the track");
    }

    @Test
    void aSliderValueIsBroughtIntoItsRangeWhereverItComesFrom() {
        long[] id = new long[1];
        recorder.onInit =
                () ->
                        id[0] =
                                HostFunctions.Screen_add_slider(
                                        screen.entityId(),
                                        "slider",
                                        "level",
                                        0,
                                        10,
                                        25.0,
                                        0,
                                        0,
                                        100,
                                        20);
        init();
        assertEquals(10.0, HostFunctions.Widget_value(id[0]), "a start past the end is the max");
        assertNull(reported());

        recorder.onInit =
                () -> {
                    long again =
                            HostFunctions.Screen_add_slider(
                                    screen.entityId(),
                                    "slider",
                                    "level",
                                    0,
                                    10,
                                    -3.0,
                                    0,
                                    0,
                                    100,
                                    20);
                    assertEquals(id[0], again, "the same name answers the same Widget");
                };
        init();
        assertEquals(0.0, HostFunctions.Widget_value(id[0]), "a redeclared value below the min");
        assertEquals(0.0, screen.widget("slider").sliderFraction());
    }

    @Test
    void aTextColourIsKeptAWidgetsColourForItsTextField() {
        long[] id = new long[1];
        recorder.onInit =
                () ->
                        id[0] =
                                HostFunctions.Screen_add_text_field(
                                        screen.entityId(), "field", "abc", 0, 0, 30, 12);
        init();
        long colour = GrugEntities.addEntity(GrugEntityType.Color, new Color(1, 2, 3, 4));
        HostFunctions.Widget_set_text_colour(id[0], colour);
        assertEquals(0x010203, screen.widget("field").textColour());
        assertNull(reported());
    }

    @Test
    void aTextColourOnAButtonIsAnErr() {
        long[] id = new long[1];
        recorder.onInit = () -> id[0] = button("button");
        init();
        long colour = GrugEntities.addEntity(GrugEntityType.Color, new Color(1, 2, 3));
        HostFunctions.Widget_set_text_colour(id[0], colour);
        String report = reported();
        assertNotNull(report);
        assertTrue(report.contains("text field"), report);
    }

    @Test
    void aWidgetWhoseScreenClosedIsAnErrSayingSo() {
        long[] id = new long[1];
        recorder.onInit = () -> id[0] = button("button");
        init();
        GrugOwnedScreens.gameRemoved(screen);

        assertFalse(HostFunctions.Widget_checked(0));
        assertTrue(reported().contains("no longer resolves"));
        assertEquals("", HostFunctions.Widget_text(id[0]));
        String report = reported();
        assertTrue(report.contains("no longer resolves"), report);
        assertTrue(report.contains("\"button\""), report);
        assertTrue(report.contains("its screen closed"), report);
        assertFalse(report.contains("collected"), report);
    }

    @Test
    void aPositionThatIsNotFiniteIsAnErr() {
        long[] id = new long[1];
        recorder.onInit = () -> id[0] = button("button");
        init();
        HostFunctions.Widget_set_position(id[0], Double.POSITIVE_INFINITY, 0);
        assertTrue(reported().contains("finite"));
        assertEquals(1.0, screen.widget("button").x(), "the widget did not move");
    }

    @Test
    void theRowResponseIsAnsweredForTheFrameTheListAsks() {
        long[] listId = new long[1];
        recorder.onInit =
                () -> {
                    listId[0] = list("list");
                    HostFunctions.Widget_set_rows(listId[0], 2);
                    HostFunctions.Widget_select(listId[0], 1);
                };
        init();
        List<Integer> asked = new ArrayList<>();
        recorder.onRow =
                () -> {
                    long responseId = recorder.lastArgumentId;
                    asked.add((int) HostFunctions.ListRowResponse_index(responseId));
                    HostFunctions.ListRowResponse_set_text(responseId, "row");
                };
        GrugOwnedScreens.prepareFrame(screen);
        assertEquals(List.of(0, 1), asked);
        assertNull(reported());
    }
}
