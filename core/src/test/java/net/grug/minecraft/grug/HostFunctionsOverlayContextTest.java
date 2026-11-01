package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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

    @BeforeEach
    @AfterEach
    void clearState() {
        if (GrugOverlay.isRender()) {
            GrugOverlay.closeRender();
        } else if (GrugOverlay.isClick()) {
            GrugOverlay.closeClick();
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
        GrugOverlay.openClick(426, 240, 125, 37, 176, 166, 310, 12, 0);
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

        assertTrue(drain().isEmpty(), "A legal pass reports nothing.");
        GrugOverlayGeometry geometry = GrugOverlay.closeRender();
        assertEquals(1, geometry.items().size());
        assertEquals(ITEM, geometry.items().get(0).item());
        assertEquals(319, geometry.items().get(0).x());
        assertEquals(1, geometry.items().get(0).y());
        assertEquals(1, geometry.boxes().size());
        assertEquals(318, geometry.boxes().get(0).x1());
        assertEquals(18, geometry.boxes().get(0).y2());
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

        List<String> reports = drain();
        assertEquals(10, reports.size(), "Every method names its own refusal.");
        assertTrue(reports.get(0).contains("OverlayRenderContext.screen_width"), reports.get(0));
        assertTrue(reports.get(9).contains("OverlayRenderContext.draw_box"), reports.get(9));
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
        HostFunctions.OverlayClickContext_take(context);

        List<String> reports = drain();
        assertEquals(10, reports.size(), "Every method names its own refusal.");
        assertTrue(reports.get(0).contains("OverlayClickContext.screen_width"), reports.get(0));
        assertTrue(reports.get(9).contains("OverlayClickContext.take"), reports.get(9));
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
        assertFalse(HostFunctions.Test_overlay_supported());
        GrugOverlay.noteSupported();
        assertTrue(HostFunctions.Test_overlay_supported());
    }
}
