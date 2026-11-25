package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.grug.minecraft.core.GrugCore;
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
 * Covers the methods the three world overlay passes reach through the context their export is
 * handed: what each answers inside its own pass, and that each is refused outside it.
 */
class HostFunctionsWorldOverlayContextTest {

    private Object savedAdapter;

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
                            if (method.isDefault()) {
                                return InvocationHandler.invokeDefault(proxy, method, args);
                            }
                            // A refused call is reported to the loader's log, which a Java test
                            // has nowhere to put; every other question fails the test.
                            if (method.getName().equals("logError")) {
                                return null;
                            }
                            throw new AssertionError(
                                    "a world overlay context test does not ask "
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
        if (GrugWorldOverlay.isHud()) {
            GrugWorldOverlay.closeHud();
        } else if (GrugWorldOverlay.isWorld()) {
            GrugWorldOverlay.closeWorld();
        } else if (GrugWorldOverlay.isKey()) {
            GrugWorldOverlay.closeKey();
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

    @Test
    void aHudContextAnswersTheFramesSizeAndRecordsItsTexts() {
        long color = GrugEntities.addEntity(GrugEntityType.Color, new Color(255, 0, 0));
        GrugWorldOverlay.openHud(426, 240);
        long context =
                GrugEntities.addEntity(
                        GrugEntityType.OverlayHudContext, GrugWorldOverlay.context());

        assertEquals(426, HostFunctions.OverlayHudContext_screen_width(context));
        assertEquals(240, HostFunctions.OverlayHudContext_screen_height(context));
        HostFunctions.OverlayHudContext_add_text(context, "Hostile 41 / 70", 4, 5, color);

        assertTrue(drain().isEmpty(), "A legal pass reports nothing.");
        List<GrugWorldOverlay.Text> texts = GrugWorldOverlay.closeHud();
        assertEquals(1, texts.size());
        assertEquals("Hostile 41 / 70", texts.get(0).text());
        assertEquals(4, texts.get(0).x());
        assertEquals(5, texts.get(0).y());
        assertEquals(0xff0000, texts.get(0).rgb());
    }

    @Test
    void aWorldContextAnswersTheCameraAndRecordsItsLines() {
        long color = GrugEntities.addEntity(GrugEntityType.Color, new Color(0, 255, 0));
        GrugWorldOverlay.openWorld(0.25, 1.5, 64, -3, 1.5, 62, -3, "coverage");
        long context =
                GrugEntities.addEntity(
                        GrugEntityType.OverlayWorldContext, GrugWorldOverlay.context());

        assertEquals(0.25, HostFunctions.OverlayWorldContext_partial_tick(context));
        assertEquals(1.5, HostFunctions.OverlayWorldContext_camera_x(context));
        assertEquals(64, HostFunctions.OverlayWorldContext_camera_y(context));
        assertEquals(-3, HostFunctions.OverlayWorldContext_camera_z(context));
        HostFunctions.OverlayWorldContext_draw_line(context, 0, 1, 2, 3, 4, 5, color);

        assertTrue(drain().isEmpty(), "A legal pass reports nothing.");
        List<GrugWorldOverlay.Line> lines = GrugWorldOverlay.closeWorld();
        assertEquals(1, lines.size());
        assertEquals(5, lines.get(0).z2());
        assertEquals(0x00ff00, lines.get(0).rgb());
    }

    @Test
    void aKeyContextAnswersTheKeyAndTheModifier() {
        GrugWorldOverlay.openKey('L', true);
        long context =
                GrugEntities.addEntity(
                        GrugEntityType.OverlayWorldKeyContext, GrugWorldOverlay.context());

        assertEquals('L', HostFunctions.OverlayWorldKeyContext_key(context));
        assertTrue(HostFunctions.OverlayWorldKeyContext_ctrl(context));
        assertTrue(drain().isEmpty());
    }

    @Test
    void everyMethodIsRefusedOutsideItsOwnPass() {
        GrugWorldOverlay.openKey('L', false);
        long keyContext =
                GrugEntities.addEntity(
                        GrugEntityType.OverlayWorldKeyContext, GrugWorldOverlay.context());
        GrugWorldOverlay.closeKey();

        HostFunctions.OverlayHudContext_screen_width(keyContext);
        HostFunctions.OverlayHudContext_screen_height(keyContext);
        HostFunctions.OverlayHudContext_add_text(keyContext, "x", 0, 0, 0);
        HostFunctions.OverlayHudContext_get_level(keyContext);
        HostFunctions.OverlayWorldContext_partial_tick(keyContext);
        HostFunctions.OverlayWorldContext_camera_x(keyContext);
        HostFunctions.OverlayWorldContext_camera_y(keyContext);
        HostFunctions.OverlayWorldContext_camera_z(keyContext);
        HostFunctions.OverlayWorldContext_draw_line(keyContext, 0, 0, 0, 1, 1, 1, 0);
        HostFunctions.OverlayWorldContext_draw_triangle(keyContext, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0);
        HostFunctions.OverlayWorldContext_get_level(keyContext);
        HostFunctions.OverlayWorldKeyContext_key(keyContext);
        HostFunctions.OverlayWorldKeyContext_ctrl(keyContext);
        HostFunctions.OverlayHudContext_top_inset(keyContext);
        HostFunctions.OverlayWorldContext_get_config(keyContext);
        HostFunctions.OverlayWorldContext_get_spawn_squares(keyContext, 1);
        HostFunctions.OverlayWorldBindingContext_id(keyContext);
        HostFunctions.OverlayWorldBindingContext_get_config(keyContext);

        List<String> reports = drain();
        assertEquals(18, reports.size(), "Every method names its own refusal.");
        assertTrue(reports.get(0).contains("OverlayHudContext.screen_width"), reports.get(0));
        assertTrue(reports.get(3).contains("OverlayHudContext.get_level"), reports.get(3));
        assertTrue(reports.get(8).contains("OverlayWorldContext.draw_line"), reports.get(8));
        assertTrue(reports.get(9).contains("OverlayWorldContext.draw_triangle"), reports.get(9));
        assertTrue(reports.get(10).contains("OverlayWorldContext.get_level"), reports.get(10));
        assertTrue(reports.get(12).contains("OverlayWorldKeyContext.ctrl"), reports.get(12));
        assertTrue(reports.get(13).contains("OverlayHudContext.top_inset"), reports.get(13));
        assertTrue(reports.get(14).contains("OverlayWorldContext.get_config"), reports.get(14));
        assertTrue(
                reports.get(15).contains("OverlayWorldContext.get_spawn_squares"), reports.get(15));
        assertTrue(reports.get(16).contains("OverlayWorldBindingContext.id"), reports.get(16));
        assertTrue(
                reports.get(17).contains("OverlayWorldBindingContext.get_config"), reports.get(17));
        for (String report : reports) {
            assertTrue(report.contains("saved in a member variable"), report);
        }
    }

    @Test
    void aContextIsRefusedByAnotherKindsMethodEvenDuringItsOwnPass() {
        GrugWorldOverlay.openHud(10, 10);
        long context =
                GrugEntities.addEntity(
                        GrugEntityType.OverlayHudContext, GrugWorldOverlay.context());

        HostFunctions.OverlayWorldContext_camera_x(context);
        HostFunctions.OverlayWorldKeyContext_key(context);

        List<String> reports = drain();
        assertEquals(2, reports.size());
        assertTrue(reports.get(0).contains("render_world"), reports.get(0));
        assertTrue(reports.get(1).contains("world_key"), reports.get(1));
        assertTrue(GrugWorldOverlay.isHud(), "The refused calls left the HUD pass open.");
    }

    @Test
    void aLineWithAnEndpointThatIsNotFiniteIsRefused() {
        assertNull(HostFunctions.worldLineProblem(0, 1, 2, 3, 4, 5));
        double[] bad = {Double.NaN, Double.POSITIVE_INFINITY};
        for (double value : bad) {
            assertNotNull(HostFunctions.worldLineProblem(value, 0, 0, 0, 0, 0));
            assertNotNull(HostFunctions.worldLineProblem(0, value, 0, 0, 0, 0));
            assertNotNull(HostFunctions.worldLineProblem(0, 0, value, 0, 0, 0));
            assertNotNull(HostFunctions.worldLineProblem(0, 0, 0, value, 0, 0));
            assertNotNull(HostFunctions.worldLineProblem(0, 0, 0, 0, value, 0));
            assertNotNull(HostFunctions.worldLineProblem(0, 0, 0, 0, 0, value));
        }
    }

    @Test
    void worldOverlaySupportIsTheAdaptersAnswer() {
        GrugWorldOverlay.noteSupported();
        assertTrue(HostFunctions.Test_world_overlay_supported(0));
    }

    @Test
    void theDrawnMarkIsReadThroughTheTestContext() {
        GrugWorldOverlay.clearDrawn();
        assertFalse(HostFunctions.TestTickContext_world_overlay_drawn(GrugTestContexts.tick()));
        GrugWorldOverlay.noteDrawn();
        assertTrue(HostFunctions.TestTickContext_world_overlay_drawn(GrugTestContexts.tick()));
        GrugWorldOverlay.clearDrawn();
    }
}
