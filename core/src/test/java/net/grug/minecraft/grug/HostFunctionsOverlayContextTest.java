package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.grug.minecraft.core.GrugCore;
import net.grug.minecraft.core.GrugTestScreens;
import net.grug.minecraft.core.ModLoaderAdapter;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

/**
 * Covers the methods an overlay pass reaches through the context its export is handed.
 *
 * <p>The two passes carry different state, so a method of one is refused during the other even with
 * the other pass's own context, and a context used after its pass closed is refused everywhere. The
 * refusal is a mod-tree defect, which a Java test can drive without a live grug state, so every
 * method's refusal is measured here and the game only has to cover the drawings themselves.
 */
class HostFunctionsOverlayContextTest {

    private static final Object ITEM = new Object();

    private Object savedAdapter;

    /** What the fake adapter answers for the item on the cursor, or null for an empty cursor. */
    private Object carriedItem = null;

    /**
     * The identity the fake adapter says the screen open now has, so a first-frame read names a
     * screen and a record from another one cannot answer for it.
     */
    private long testScreenIdentity;

    /**
     * Installs a fake adapter for the two overlay reads, which go through it because a dedicated
     * server asks the joined client instead of answering for itself (#1099). Every default method
     * runs its own body here, so the marks a loader keeps locally are the ones a test reads, and
     * any other call fails rather than quietly answering null.
     */
    @BeforeEach
    void installAdapter() throws Exception {
        Field field = GrugCore.class.getDeclaredField("adapter");
        field.setAccessible(true);
        savedAdapter = field.get(null);
        field.set(
                null,
                Proxy.newProxyInstance(
                        getClass().getClassLoader(),
                        new Class<?>[] {ModLoaderAdapter.class},
                        (proxy, method, args) -> {
                            if (method.getName().equals("screenIdentityForTest")) {
                                return testScreenIdentity;
                            }
                            if (method.getName().equals("carriedItem")) {
                                return carriedItem;
                            }
                            if (method.isDefault()) {
                                return InvocationHandler.invokeDefault(proxy, method, args);
                            }
                            // A refused call is reported to the loader's log, which a Java test
                            // has nowhere to put; every other question fails the test.
                            if (method.getName().equals("logError")) {
                                return null;
                            }
                            throw new AssertionError(
                                    "an overlay context test does not ask "
                                            + method.getName()
                                            + " of the adapter");
                        }));
    }

    @AfterEach
    void restoreAdapter() throws Exception {
        Field field = GrugCore.class.getDeclaredField("adapter");
        field.setAccessible(true);
        field.set(null, savedAdapter);
    }

    @BeforeEach
    @AfterEach
    void clearState() {
        if (GrugOverlay.isRender()) {
            GrugOverlay.closeRender();
        } else if (GrugOverlay.isClick()) {
            GrugOverlay.closeClick();
        } else if (GrugOverlay.isKey()) {
            GrugOverlay.closeKey();
        } else if (GrugOverlay.isScroll()) {
            GrugOverlay.closeScroll();
        }
        GrugEntities.entityData.clear();
        drain();
    }

    private static List<String> drain() {
        synchronized (GrugErrors.runtimeErrorQueue) {
            List<String> reports = new ArrayList<>(GrugErrors.runtimeErrorQueue);
            GrugErrors.runtimeErrorQueue.clear();
            return reports;
        }
    }

    private static void openRender() {
        GrugOverlay.openRender(426, 240, 125, 37, 176, 166, 310, 12);
    }

    private static void openClick() {
        GrugOverlay.openClick(426, 240, 125, 37, 176, 166, 310, 12, 0, false);
    }

    private static long renderContext() {
        return GrugEntities.addEntity(GrugEntityType.OverlayRenderContext, GrugOverlay.context());
    }

    private static long clickContext() {
        return GrugEntities.addEntity(GrugEntityType.OverlayClickContext, GrugOverlay.context());
    }

    @Test
    void aRenderContextAnswersTheScreensNumbersAndRecordsItsDraws() {
        long item = GrugEntities.addEntity(GrugEntityType.Item, ITEM);
        openRender();
        long context = renderContext();

        assertEquals(426, HostFunctions.OverlayRenderContext_screen_width(context));
        assertEquals(240, HostFunctions.OverlayRenderContext_screen_height(context));
        assertEquals(125, HostFunctions.OverlayRenderContext_gui_left(context));
        assertEquals(37, HostFunctions.OverlayRenderContext_gui_top(context));
        assertEquals(176, HostFunctions.OverlayRenderContext_gui_width(context));
        assertEquals(166, HostFunctions.OverlayRenderContext_gui_height(context));
        assertEquals(310, HostFunctions.OverlayRenderContext_mouse_x(context));
        assertEquals(12, HostFunctions.OverlayRenderContext_mouse_y(context));

        HostFunctions.OverlayRenderContext_draw_item(context, item, 319, 1);
        HostFunctions.OverlayRenderContext_draw_box(context, 318, 0, 336, 18);
        HostFunctions.OverlayRenderContext_draw_tooltip(context, "Click to delete.", 310, 12);

        assertTrue(drain().isEmpty(), "A legal pass reports nothing.");
        GrugOverlayGeometry geometry = GrugOverlay.closeRender();
        assertEquals(1, geometry.tooltips().size());
        assertEquals("Click to delete.", geometry.tooltips().get(0).text());
        assertEquals(310, geometry.tooltips().get(0).x());
        assertEquals(12, geometry.tooltips().get(0).y());
        assertEquals(1, geometry.items().size());
        assertEquals(ITEM, geometry.items().get(0).item());
        assertEquals(319, geometry.items().get(0).x());
        assertEquals(1, geometry.items().get(0).y());
        assertEquals(1, geometry.boxes().size());
        assertEquals(318, geometry.boxes().get(0).x1());
        assertEquals(18, geometry.boxes().get(0).y2());
    }

    @Test
    void aRenderPassRecordsItsTextsInOrderAndAClosedPassRecordsNone() {
        openRender();
        assertTrue(GrugOverlay.recordText("first", 4, 5, 0xFFFFFF));
        assertTrue(GrugOverlay.recordText("second", 6, 7, 0x123456));
        GrugOverlayGeometry geometry = GrugOverlay.closeRender();
        assertEquals(2, geometry.texts().size());
        assertEquals("first", geometry.texts().get(0).text());
        assertEquals(4, geometry.texts().get(0).x());
        assertEquals(5, geometry.texts().get(0).y());
        assertEquals(0xFFFFFF, geometry.texts().get(0).rgb());
        assertEquals("second", geometry.texts().get(1).text());
        assertFalse(GrugOverlay.recordText("late", 0, 0, 0));

        openRender();
        assertTrue(GrugOverlay.closeRender().texts().isEmpty(), "The next pass starts empty.");
    }

    @Test
    void aRenderPassRecordsItsTooltipsInOrderAndAClosedPassRecordsNone() {
        openRender();
        assertTrue(GrugOverlay.recordTooltip("first", 4, 5));
        assertTrue(GrugOverlay.recordTooltip("second", 6, 7));
        GrugOverlayGeometry geometry = GrugOverlay.closeRender();
        assertEquals(2, geometry.tooltips().size());
        assertEquals("first", geometry.tooltips().get(0).text());
        assertEquals(4, geometry.tooltips().get(0).x());
        assertEquals(5, geometry.tooltips().get(0).y());
        assertEquals("second", geometry.tooltips().get(1).text());
        assertFalse(GrugOverlay.recordTooltip("late", 0, 0));

        openRender();
        assertTrue(GrugOverlay.closeRender().tooltips().isEmpty(), "The next pass starts empty.");
    }

    @Test
    void aTooltipNeedsAFinitePosition() {
        openRender();
        long context = renderContext();

        HostFunctions.OverlayRenderContext_draw_tooltip(context, "nan", Double.NaN, 0);
        HostFunctions.OverlayRenderContext_draw_tooltip(context, "inf", 0, Double.NaN);

        List<String> reports = drain();
        assertEquals(2, reports.size());
        assertTrue(reports.get(0).contains("draw_tooltip"), reports.get(0));
        assertTrue(reports.get(0).contains("finite"), reports.get(0));
        assertTrue(GrugOverlay.closeRender().tooltips().isEmpty(), "Neither tooltip was drawn.");
    }

    @Test
    void aRenderContextReadsTheItemOnTheCursor() {
        openRender();
        long context = renderContext();
        GrugOption empty = optionOf(HostFunctions.OverlayRenderContext_carried_item(context));
        assertFalse(empty.has(), "an empty cursor has no item");

        carriedItem = ITEM;
        GrugOption option = optionOf(HostFunctions.OverlayRenderContext_carried_item(context));
        assertTrue(option.has());
        long item = (Long) option.value();
        assertEquals(ITEM, GrugEntities.entityData.get(item).object);
        assertTrue(drain().isEmpty(), "A legal read reports nothing.");
        GrugOverlay.closeRender();
    }

    private static GrugOption optionOf(long optionId) {
        return (GrugOption) GrugEntities.entityData.get(optionId).object;
    }

    @Test
    void theScreenSerialChangesWithTheScreenAFrameIsDrawnOn() {
        Object first = new Object();
        Object second = new Object();
        GrugOverlay.noteFrame(first);
        openRender();
        double serial = HostFunctions.OverlayRenderContext_screen_serial(renderContext());
        GrugOverlay.closeRender();

        GrugOverlay.noteFrame(first);
        openRender();
        assertEquals(serial, HostFunctions.OverlayRenderContext_screen_serial(renderContext()));
        GrugOverlay.closeRender();

        GrugOverlay.noteFrame(second);
        openRender();
        assertEquals(serial + 1, HostFunctions.OverlayRenderContext_screen_serial(renderContext()));
        GrugOverlay.closeRender();
    }

    @Test
    void aTestsKeyNumberNamesTheKeyTheGameLooksUp() {
        assertEquals("O", GrugOverlay.keyNameForCode('O'));
        assertEquals("7", GrugOverlay.keyNameForCode('7'));
        assertEquals("SPACE", GrugOverlay.keyNameForCode(32));
        assertEquals("BACK", GrugOverlay.keyNameForCode(8));
        assertEquals("LEFT", GrugOverlay.keyNameForCode(263));
        assertEquals("HOME", GrugOverlay.keyNameForCode(268));
        assertEquals("DELETE", GrugOverlay.keyNameForCode(261));
        assertEquals(263, GrugOverlay.keyCodeForName("LEFT"));
        assertEquals(268, GrugOverlay.keyCodeForName("HOME"));
        assertEquals(261, GrugOverlay.keyCodeForName("DELETE"));
        assertEquals(-1, GrugOverlay.keyCodeForName("LSHIFT"), "A modifier is not asked.");
    }

    @Test
    void aClickContextAnswersItsPressAndTakesIt() {
        openClick();
        long context = clickContext();

        assertEquals(426, HostFunctions.OverlayClickContext_screen_width(context));
        assertEquals(240, HostFunctions.OverlayClickContext_screen_height(context));
        assertEquals(125, HostFunctions.OverlayClickContext_gui_left(context));
        assertEquals(37, HostFunctions.OverlayClickContext_gui_top(context));
        assertEquals(176, HostFunctions.OverlayClickContext_gui_width(context));
        assertEquals(166, HostFunctions.OverlayClickContext_gui_height(context));
        assertEquals(310, HostFunctions.OverlayClickContext_mouse_x(context));
        assertEquals(12, HostFunctions.OverlayClickContext_mouse_y(context));
        assertEquals(0, HostFunctions.OverlayClickContext_button(context));
        assertFalse(HostFunctions.OverlayClickContext_shift(context));

        HostFunctions.OverlayClickContext_take(context);

        assertTrue(drain().isEmpty(), "A legal take reports nothing.");
        assertTrue(GrugOverlay.closeClick(), "The press was taken.");
    }

    @Test
    void aDrawOutsideARenderPassIsRefusedByEveryMethod() {
        openClick();
        long context = clickContext();
        GrugOverlay.closeClick();

        HostFunctions.OverlayRenderContext_screen_width(context);
        HostFunctions.OverlayRenderContext_screen_height(context);
        HostFunctions.OverlayRenderContext_gui_left(context);
        HostFunctions.OverlayRenderContext_gui_top(context);
        HostFunctions.OverlayRenderContext_gui_width(context);
        HostFunctions.OverlayRenderContext_gui_height(context);
        HostFunctions.OverlayRenderContext_mouse_x(context);
        HostFunctions.OverlayRenderContext_mouse_y(context);
        HostFunctions.OverlayRenderContext_draw_item(context, 1, 0, 0);
        HostFunctions.OverlayRenderContext_draw_box(context, 0, 0, 1, 1);
        HostFunctions.OverlayRenderContext_draw_tooltip(context, "late", 0, 0);
        HostFunctions.OverlayRenderContext_carried_item(context);
        HostFunctions.OverlayRenderContext_screen_serial(context);

        List<String> reports = drain();
        assertEquals(13, reports.size(), "Every method names its own refusal.");
        assertTrue(reports.get(0).contains("OverlayRenderContext.screen_width"), reports.get(0));
        assertTrue(reports.get(9).contains("OverlayRenderContext.draw_box"), reports.get(9));
        assertTrue(reports.get(10).contains("OverlayRenderContext.draw_tooltip"), reports.get(10));
        assertTrue(reports.get(11).contains("OverlayRenderContext.carried_item"), reports.get(11));
        assertTrue(reports.get(12).contains("OverlayRenderContext.screen_serial"), reports.get(12));
        for (String report : reports) {
            assertTrue(report.contains("saved in a member variable"), report);
        }
    }

    @Test
    void aPressOutsideAClickPassIsRefusedByEveryMethod() {
        openRender();
        long context = renderContext();
        GrugOverlay.closeRender();

        HostFunctions.OverlayClickContext_screen_width(context);
        HostFunctions.OverlayClickContext_screen_height(context);
        HostFunctions.OverlayClickContext_gui_left(context);
        HostFunctions.OverlayClickContext_gui_top(context);
        HostFunctions.OverlayClickContext_gui_width(context);
        HostFunctions.OverlayClickContext_gui_height(context);
        HostFunctions.OverlayClickContext_mouse_x(context);
        HostFunctions.OverlayClickContext_mouse_y(context);
        HostFunctions.OverlayClickContext_button(context);
        HostFunctions.OverlayClickContext_shift(context);
        HostFunctions.OverlayClickContext_take(context);
        HostFunctions.OverlayClickContext_close_screen(context);

        List<String> reports = drain();
        assertEquals(12, reports.size(), "Every method names its own refusal.");
        assertTrue(reports.get(0).contains("OverlayClickContext.screen_width"), reports.get(0));
        assertTrue(reports.get(10).contains("OverlayClickContext.take"), reports.get(10));
        assertTrue(reports.get(11).contains("OverlayClickContext.close_screen"), reports.get(11));
        for (String report : reports) {
            assertTrue(report.contains("saved in a member variable"), report);
        }
    }

    @Test
    void aClickContextIsRefusedByARenderMethodEvenDuringItsOwnPass() {
        openClick();
        long context = clickContext();

        HostFunctions.OverlayRenderContext_screen_width(context);

        List<String> reports = drain();
        assertEquals(1, reports.size());
        assertTrue(reports.get(0).contains("OverlayRenderContext.screen_width"), reports.get(0));
        assertTrue(GrugOverlay.isOpen(), "The refused call left the click pass open.");
        assertFalse(GrugOverlay.closeClick(), "It did not take the press.");
    }

    @Test
    void aRenderContextIsRefusedByAClickMethodEvenDuringItsOwnPass() {
        openRender();
        long context = renderContext();

        HostFunctions.OverlayClickContext_take(context);

        List<String> reports = drain();
        assertEquals(1, reports.size());
        assertTrue(reports.get(0).contains("OverlayClickContext.take"), reports.get(0));
        assertTrue(GrugOverlay.closeRender().boxes().isEmpty());
    }

    @Test
    void overlaySupportedStartsFalseAndStaysTrueOnceNoted() {
        assertFalse(HostFunctions.Test_overlay_supported(0));
        GrugOverlay.noteSupported();
        assertTrue(HostFunctions.Test_overlay_supported(0));
    }

    @Test
    void overlayDrawnStartsFalseAndStaysTrueOnceNoted() {
        GrugOverlay.screenOpened();
        assertFalse(HostFunctions.TestTickContext_overlay_drawn(GrugTestContexts.tick()));
        GrugOverlay.noteDrawn();
        assertTrue(HostFunctions.TestTickContext_overlay_drawn(GrugTestContexts.tick()));
    }

    @Test
    void aDrawnMarkIsClearedWhenAnotherModsOverlayIsAskedFor() {
        // The client of a server-driven run is asked about one mod's overlay after another, and a
        // frame that drew the first mod's overlay says nothing about the second one's.
        GrugTestState.currentTestMod = "coverage";
        GrugOverlay.noteDrawn();
        assertTrue(GrugOverlay.drawnForTest("coverage"), "the same mod keeps its mark");
        assertFalse(GrugOverlay.drawnForTest("jei"), "another mod starts from a clean mark");
        assertEquals("jei", GrugTestState.currentTestMod);
        GrugTestState.currentTestMod = null;
    }

    @Test
    void aNewlyOpenedScreenHasNotBeenDrawnYet() {
        GrugOverlay.screenOpened();
        GrugOverlay.noteDrawn();
        assertTrue(GrugOverlay.overlayDrawn());

        // An earlier test's screen was drawn, but the one that just opened has not been, so a test
        // that opened its own screen has to wait for a frame instead of reading the old mark.
        GrugOverlay.screenOpened();
        assertFalse(GrugOverlay.overlayDrawn());
    }

    @Test
    void overlayFirstFrameClearedRecordsWhetherTheMarkWasSetWhenTheScreenFirstDrew() {
        GrugTestState.currentTestMod = null;
        long context = GrugTestContexts.tick();
        GrugOverlay.screenOpened();
        Object screen = new Object();
        testScreenIdentity = GrugTestScreens.identityOf(screen);

        // Before the screen's first frame the record says nothing, so this is not read as clean.
        assertFalse(
                HostFunctions.TestTickContext_overlay_first_frame_cleared(context),
                "no frame of the screen has begun yet");

        // A hook that ran before the first frame left the mark clear when the frame began.
        GrugOverlay.noteFrame(screen);
        assertTrue(HostFunctions.TestTickContext_overlay_first_frame_cleared(context));

        // A new screen whose hook never ran has drawn no frame yet, so the record
        // still describes the first screen, and a read that names the new one must not take it.
        Object reopened = new Object();
        testScreenIdentity = GrugTestScreens.identityOf(reopened);
        assertFalse(
                HostFunctions.TestTickContext_overlay_first_frame_cleared(context),
                "the first screen's record is not about the screen open now");

        // Once the reopened screen draws its first frame, that frame began with the previous
        // screen's mark, which is what a forwarded test asserts against.
        GrugOverlay.noteDrawn();
        GrugOverlay.noteFrame(reopened);
        assertFalse(HostFunctions.TestTickContext_overlay_first_frame_cleared(context));
        GrugTestState.currentTestMod = null;
    }

    private static long keyContext() {
        return GrugEntities.addEntity(GrugEntityType.OverlayKeyContext, GrugOverlay.context());
    }

    @Test
    void aKeyContextAnswersItsKeyAndTakesIt() {
        GrugOverlay.openKey(79, 0, true, false, false);
        long context = keyContext();

        assertTrue(GrugOverlay.isKey());
        assertFalse(GrugOverlay.isClick(), "A key pass is not a click pass.");
        assertEquals(79, HostFunctions.OverlayKeyContext_key(context));
        assertTrue(HostFunctions.OverlayKeyContext_ctrl(context));
        assertFalse(HostFunctions.OverlayKeyContext_shift(context));
        HostFunctions.OverlayKeyContext_take(context);

        assertTrue(drain().isEmpty(), "A legal key pass reports nothing.");
        assertTrue(GrugOverlay.closeKey(), "The key was taken.");
        assertFalse(GrugOverlay.isOpen());
    }

    @Test
    void aKeyContextSaysWhetherShiftWasHeld() {
        GrugOverlay.openKey(263, 0, false, true, false);
        long context = keyContext();

        assertTrue(HostFunctions.OverlayKeyContext_shift(context));
        assertFalse(HostFunctions.OverlayKeyContext_ctrl(context));
        assertFalse(GrugOverlay.closeKey());
    }

    @Test
    void aKeyThatIsNotTakenIsNotReportedTaken() {
        GrugOverlay.openKey(80, 0, false, false, false);
        assertFalse(HostFunctions.OverlayKeyContext_ctrl(keyContext()));
        assertFalse(GrugOverlay.closeKey());
    }

    @Test
    void aKeyContextAnswersTheCharacterItTypedAndWhetherItIsARepeat() {
        GrugOverlay.openKey(0, 59, false, false, true);
        long context = keyContext();

        assertEquals(0, HostFunctions.OverlayKeyContext_key(context), "A ; has no key number.");
        assertEquals(59, HostFunctions.OverlayKeyContext_character(context));
        assertTrue(HostFunctions.OverlayKeyContext_repeat(context));
        assertFalse(HostFunctions.OverlayKeyContext_ctrl(context));
        assertFalse(GrugOverlay.closeKey(), "Reading the context did not take it.");
    }

    @Test
    void onlyAPrintableCharacterIsTypedText() {
        assertEquals(59, GrugOverlay.characterCode(';'));
        assertEquals(64, GrugOverlay.characterCode('@'));
        assertEquals(32, GrugOverlay.characterCode(' '));
        assertEquals('a', GrugOverlay.characterCode('a'));
        assertEquals(0, GrugOverlay.characterCode(0));
        assertEquals(0, GrugOverlay.characterCode(8));
        assertEquals(0, GrugOverlay.characterCode(31));
        assertEquals(0, GrugOverlay.characterCode(127));
    }

    @Test
    void aSurrogateIsHalfACodePointAndIsNotTypedText() {
        // A character above the Basic Multilingual Plane arrives as two surrogate events on every
        // loader (1.20.6 splits the code point itself, LWJGL 2 delivers one), and the grug function
        // from_character refuses a surrogate, so neither half is delivered. The character either
        // side of the range is a whole character and stays (#1741).
        assertEquals(0xD7FF, GrugOverlay.characterCode(0xD7FF));
        assertEquals(0, GrugOverlay.characterCode(0xD800));
        assertEquals(0, GrugOverlay.characterCode(0xDBFF));
        assertEquals(0, GrugOverlay.characterCode(0xDC00));
        assertEquals(0, GrugOverlay.characterCode(0xDFFF));
        assertEquals(0xE000, GrugOverlay.characterCode(0xE000));
    }

    @Test
    void aCharacterThatAltGrProducesIsNotAChord() {
        // Windows reports an AltGr press as control and alt together (it sends a spurious left
        // control down beside the right alt, and GLFW keeps both bits on the character event), so a
        // German @ or a French ~ reaches an overlay with control held. It is the character of the
        // layout and is typed, not a chord (#1741).
        assertFalse(GrugOverlay.characterEventIsChord(false, false));
        assertFalse(GrugOverlay.characterEventIsChord(false, true));
        assertTrue(GrugOverlay.characterEventIsChord(true, false));
        assertFalse(GrugOverlay.characterEventIsChord(true, true));
    }

    @Test
    void aTestsPressTypesTheCharacterItsKeyProducesWithoutShift() {
        assertEquals('a', GrugOverlay.typedCharacterFor('A'));
        assertEquals('z', GrugOverlay.typedCharacterFor('Z'));
        assertEquals('7', GrugOverlay.typedCharacterFor('7'));
        assertEquals(32, GrugOverlay.typedCharacterFor(32));
        assertEquals(59, GrugOverlay.typedCharacterFor(59));
        assertEquals(64, GrugOverlay.typedCharacterFor(64));
        assertEquals(0, GrugOverlay.typedCharacterFor(8));
    }

    @Test
    void aShiftedTestsPressTypesTheCharacterShiftPutsOnTheKey() {
        // Shift changes the character as a real shift press does: a letter becomes its upper case
        // letter, and a digit or punctuation key becomes the symbol above it on a US keyboard. A
        // press of M with shift must not deliver the pair key 77 and character m, which no keyboard
        // produces (#1748).
        assertEquals('M', GrugOverlay.typedCharacterFor('M', true));
        assertEquals('m', GrugOverlay.typedCharacterFor('M', false));
        assertEquals('!', GrugOverlay.typedCharacterFor('1', true));
        assertEquals('@', GrugOverlay.typedCharacterFor('2', true));
        assertEquals(':', GrugOverlay.typedCharacterFor(';', true));
        assertEquals('~', GrugOverlay.typedCharacterFor('`', true));
        assertEquals('|', GrugOverlay.typedCharacterFor('\\', true));
        assertEquals('"', GrugOverlay.typedCharacterFor('\'', true));
        assertEquals(
                '!', GrugOverlay.typedCharacterFor('!', true), "A shifted character stays itself.");
        assertEquals(
                ' ',
                GrugOverlay.typedCharacterFor(' ', true),
                "Shift keeps the space bar's space.");
        assertEquals(0, GrugOverlay.typedCharacterFor(8, true));
        assertEquals(0, GrugOverlay.typedCharacterFor(259, true));
    }

    @Test
    void aKeyMethodOutsideAKeyPassIsRefused() {
        GrugOverlay.openKey(79, 0, true, false, false);
        long context = keyContext();
        GrugOverlay.closeKey();

        HostFunctions.OverlayKeyContext_key(context);
        HostFunctions.OverlayKeyContext_character(context);
        HostFunctions.OverlayKeyContext_ctrl(context);
        HostFunctions.OverlayKeyContext_shift(context);
        HostFunctions.OverlayKeyContext_repeat(context);
        HostFunctions.OverlayKeyContext_take(context);

        List<String> reports = drain();
        assertEquals(6, reports.size());
        assertTrue(reports.get(0).contains("OverlayKeyContext.key"), reports.get(0));
        assertTrue(reports.get(1).contains("OverlayKeyContext.character"), reports.get(1));
        assertTrue(reports.get(3).contains("OverlayKeyContext.shift"), reports.get(3));
        assertTrue(reports.get(4).contains("OverlayKeyContext.repeat"), reports.get(4));
        assertTrue(reports.get(5).contains("OverlayKeyContext.take"), reports.get(5));
    }

    @Test
    void aKeyContextIsRefusedByAClickMethodAndTheOtherWayRound() {
        GrugOverlay.openKey(79, 0, false, false, false);
        long context = keyContext();
        HostFunctions.OverlayClickContext_take(context);
        assertEquals(1, drain().size());
        assertFalse(GrugOverlay.closeKey(), "The click method did not take the key.");

        openClick();
        HostFunctions.OverlayKeyContext_take(clickContext());
        assertEquals(1, drain().size());
        assertFalse(GrugOverlay.closeClick());
    }

    @Test
    void passesCannotBeClosedAsAnotherKind() {
        GrugOverlay.openKey(79, 0, false, false, false);
        org.junit.jupiter.api.Assertions.assertThrows(
                RuntimeException.class, GrugOverlay::closeClick);
        org.junit.jupiter.api.Assertions.assertThrows(
                RuntimeException.class, GrugOverlay::closeRender);
        GrugOverlay.closeKey();
        openClick();
        org.junit.jupiter.api.Assertions.assertThrows(
                RuntimeException.class, GrugOverlay::closeKey);
    }

    @Test
    void onlyALetterOrADigitKeyNameIsAKeyCode() {
        assertEquals('O', GrugOverlay.keyCodeForName("O"));
        assertEquals('O', GrugOverlay.keyCodeForName("o"));
        assertEquals('7', GrugOverlay.keyCodeForName("7"));
        assertEquals(32, GrugOverlay.keyCodeForName("SPACE"));
        assertEquals(8, GrugOverlay.keyCodeForName("BACK"));
        assertEquals(-1, GrugOverlay.keyCodeForName(null));
        assertEquals(-1, GrugOverlay.keyCodeForName("NUMPAD7"));
        assertEquals(-1, GrugOverlay.keyCodeForName(""));
        assertEquals(-1, GrugOverlay.keyCodeForName("-"));
        assertEquals(-1, GrugOverlay.keyCodeForName("@"));
        assertEquals(-1, GrugOverlay.keyCodeForName("["));
        assertEquals(-1, GrugOverlay.keyCodeForName("/"));
        assertEquals(-1, GrugOverlay.keyCodeForName(":"));

        assertEquals(256, GrugOverlay.keyCodeForName("ESCAPE"));
        assertEquals(257, GrugOverlay.keyCodeForName("RETURN"));
        assertEquals(260, GrugOverlay.keyCodeForName("INSERT"));
        assertEquals(261, GrugOverlay.keyCodeForName("DELETE"));
        assertEquals(262, GrugOverlay.keyCodeForName("RIGHT"));
        assertEquals(263, GrugOverlay.keyCodeForName("LEFT"));
        assertEquals(264, GrugOverlay.keyCodeForName("DOWN"));
        assertEquals(265, GrugOverlay.keyCodeForName("UP"));
        assertEquals(266, GrugOverlay.keyCodeForName("PRIOR"));
        assertEquals(267, GrugOverlay.keyCodeForName("NEXT"));
        assertEquals(268, GrugOverlay.keyCodeForName("HOME"));
        assertEquals(269, GrugOverlay.keyCodeForName("END"));

        // Every key the API names round-trips through the name a loader that numbers its keys looks
        // up, so the gate covers all twelve without a game run. 258 and 259 are the two GLFW codes
        // in that range that the API does not name (#1748).
        for (int code = 256; code <= 269; code++) {
            if (code == 258 || code == 259) {
                assertFalse(GrugOverlay.isNamedKey(code), "Key " + code + " is not named.");
                continue;
            }
            assertEquals(
                    code,
                    GrugOverlay.keyCodeForName(GrugOverlay.keyNameForCode(code)),
                    "Key " + code + ".");
        }
    }

    @Test
    void onlyAKeyWithNoCharacterOfItsOwnIsANamedKey() {
        for (int code = 256; code <= 269; code++) {
            // Tab and backspace are the two GLFW codes in that range that no overlay is given:
            // backspace is 8 in the API and tab is not a key a text field reads.
            boolean expected = code != 258 && code != 259;
            assertEquals(expected, GrugOverlay.isNamedKey(code), "Key " + code + ".");
        }
        assertFalse(GrugOverlay.isNamedKey(8));
        assertFalse(GrugOverlay.isNamedKey(76));
        assertFalse(GrugOverlay.isNamedKey(270));
    }

    @Test
    void aScreenKeyIsAWholeNumberFromZeroUp() {
        assertEquals(null, HostFunctions.screenKeyProblem(79));
        assertTrue(HostFunctions.screenKeyProblem(Double.NaN).contains("whole number"));
        assertTrue(HostFunctions.screenKeyProblem(-1).contains("whole number"));
        assertTrue(HostFunctions.screenKeyProblem(79.5).contains("whole number"));
    }

    private static void openScroll(double delta) {
        GrugOverlay.openScroll(426, 240, 125, 37, 176, 166, 310, 12, delta);
    }

    private static long scrollContext() {
        return GrugEntities.addEntity(GrugEntityType.OverlayScrollContext, GrugOverlay.context());
    }

    @Test
    void aScrollContextAnswersTheScreensNumbersAndTheWheelAndTakesIt() {
        openScroll(-1.5);
        long context = scrollContext();

        assertTrue(GrugOverlay.isScroll());
        assertFalse(GrugOverlay.isClick(), "A scroll pass is not a click pass.");
        assertFalse(GrugOverlay.isKey(), "A scroll pass is not a key pass.");
        assertEquals(426, HostFunctions.OverlayScrollContext_screen_width(context));
        assertEquals(240, HostFunctions.OverlayScrollContext_screen_height(context));
        assertEquals(125, HostFunctions.OverlayScrollContext_gui_left(context));
        assertEquals(37, HostFunctions.OverlayScrollContext_gui_top(context));
        assertEquals(176, HostFunctions.OverlayScrollContext_gui_width(context));
        assertEquals(166, HostFunctions.OverlayScrollContext_gui_height(context));
        assertEquals(310, HostFunctions.OverlayScrollContext_mouse_x(context));
        assertEquals(12, HostFunctions.OverlayScrollContext_mouse_y(context));
        assertEquals(-1.5, HostFunctions.OverlayScrollContext_delta(context));
        HostFunctions.OverlayScrollContext_take(context);

        assertTrue(drain().isEmpty(), "A legal scroll pass reports nothing.");
        assertTrue(GrugOverlay.closeScroll(), "The scroll was taken.");
        assertFalse(GrugOverlay.isOpen());
        assertEquals(0.0, GrugOverlay.scrollDelta(), "The wheel is forgotten with the pass.");
    }

    @Test
    void aScrollThatIsNotTakenIsNotReportedTaken() {
        openScroll(1);
        assertEquals(1, HostFunctions.OverlayScrollContext_delta(scrollContext()));
        assertFalse(GrugOverlay.closeScroll());
    }

    @Test
    void aScrollMethodOutsideAScrollPassIsRefused() {
        openScroll(1);
        long context = scrollContext();
        GrugOverlay.closeScroll();

        HostFunctions.OverlayScrollContext_screen_width(context);
        HostFunctions.OverlayScrollContext_screen_height(context);
        HostFunctions.OverlayScrollContext_gui_left(context);
        HostFunctions.OverlayScrollContext_gui_top(context);
        HostFunctions.OverlayScrollContext_gui_width(context);
        HostFunctions.OverlayScrollContext_gui_height(context);
        HostFunctions.OverlayScrollContext_mouse_x(context);
        HostFunctions.OverlayScrollContext_mouse_y(context);
        HostFunctions.OverlayScrollContext_delta(context);
        HostFunctions.OverlayScrollContext_take(context);

        List<String> reports = drain();
        assertEquals(10, reports.size());
        assertTrue(reports.get(0).contains("OverlayScrollContext.screen_width"), reports.get(0));
        assertTrue(reports.get(9).contains("OverlayScrollContext.take"), reports.get(9));
    }

    @Test
    void aScrollContextIsRefusedByOtherPassesMethodsAndTheOtherWayRound() {
        openScroll(1);
        long context = scrollContext();
        HostFunctions.OverlayClickContext_take(context);
        HostFunctions.OverlayKeyContext_take(context);
        assertEquals(2, drain().size());
        assertFalse(GrugOverlay.closeScroll(), "Another pass's take did not take the scroll.");

        openClick();
        HostFunctions.OverlayScrollContext_take(clickContext());
        assertEquals(1, drain().size());
        assertFalse(GrugOverlay.closeClick());

        openRender();
        HostFunctions.OverlayScrollContext_delta(renderContext());
        assertEquals(1, drain().size());
    }

    @Test
    void passesCannotBeClosedAsAScrollPassOrTheOtherWayRound() {
        openScroll(1);
        org.junit.jupiter.api.Assertions.assertThrows(
                RuntimeException.class, GrugOverlay::closeClick);
        org.junit.jupiter.api.Assertions.assertThrows(
                RuntimeException.class, GrugOverlay::closeKey);
        org.junit.jupiter.api.Assertions.assertThrows(
                RuntimeException.class, GrugOverlay::closeRender);
        GrugOverlay.closeScroll();
        openClick();
        org.junit.jupiter.api.Assertions.assertThrows(
                RuntimeException.class, GrugOverlay::closeScroll);
    }

    @Test
    void aScreenScrollNeedsAFinitePositionAndDelta() {
        assertEquals(null, HostFunctions.screenScrollProblem(10, 20, -1));
        assertEquals(null, HostFunctions.screenScrollProblem(10, 20, 0.5));
        assertTrue(HostFunctions.screenScrollProblem(Double.NaN, 0, 1).contains("position"));
        assertTrue(
                HostFunctions.screenScrollProblem(0, Double.POSITIVE_INFINITY, 1)
                        .contains("position"));
        assertTrue(HostFunctions.screenScrollProblem(0, 0, Double.NaN).contains("delta"));
    }
}
