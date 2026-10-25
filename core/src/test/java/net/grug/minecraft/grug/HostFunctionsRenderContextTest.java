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
 * Covers the methods a render pass reaches through the context its export is handed.
 *
 * <p>The point of the context is that it can only be used while the export it was passed to is
 * running. A script that saves it in a member variable and uses it later is refused, and that
 * refusal is a report rather than a silent no-op. The refusal is a mod-tree defect, which a Java
 * test can drive without a live grug state, so every method's refusal is measured here and a game
 * only has to cover the drawings themselves.
 */
class HostFunctionsRenderContextTest {

    private static final Object ITEM = new Object();

    @BeforeEach
    @AfterEach
    void clearState() {
        if (GrugRenderPass.isFrame()) {
            GrugRenderPass.closeFrame();
        } else if (GrugRenderPass.isOpen()) {
            GrugRenderPass.close();
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

    private static long contextId() {
        return GrugEntities.addEntity(GrugEntityType.RenderContext, GrugRenderPass.context());
    }

    @Test
    void aChunkContextDrawsABox() {
        GrugRenderPass.open();
        long context = contextId();

        HostFunctions.RenderContext_draw_box(context, 0.25, 0.25, 0.25, 0.75, 0.75, 0.75);

        assertTrue(drain().isEmpty(), "A legal draw reports nothing.");
        assertEquals(1, GrugRenderPass.close().boxes().size());
    }

    @Test
    void aFrameContextDrawsAStackAndAnswersTheFramesNumbers() {
        long item = GrugEntities.addEntity(GrugEntityType.Item, ITEM);
        GrugRenderPass.openFrame(0.25, 270.0);
        long context = contextId();

        HostFunctions.RenderFrameContext_draw_item(context, item, 2, 7, 0.25, 0.5, 0.75);

        assertEquals(0.25, HostFunctions.RenderFrameContext_partial_tick(context));
        assertEquals(270.0, HostFunctions.RenderFrameContext_camera_yaw(context));
        assertTrue(drain().isEmpty());
        GrugItemDraw draw = GrugRenderPass.closeFrame().items().get(0);
        assertEquals(ITEM, draw.item());
        assertEquals(2, draw.damage());
        assertEquals(7, draw.count());
    }

    @Test
    void aContextSavedAfterItsPassIsRefusedByEveryMethod() {
        GrugRenderPass.open();
        long context = contextId();
        GrugRenderPass.close();

        HostFunctions.RenderContext_draw_box(context, 0, 0, 0, 1, 1, 1);
        HostFunctions.RenderContext_draw_model(context, "ignored.json", 0, 0, 0, 0, 0, 0);
        HostFunctions.RenderFrameContext_draw_item(context, 1, 0, 1, 0, 0, 0);
        HostFunctions.RenderFrameContext_partial_tick(context);
        HostFunctions.RenderFrameContext_camera_yaw(context);

        List<String> reports = drain();
        assertEquals(5, reports.size(), "Every method names its own refusal.");
        assertTrue(reports.get(0).contains("RenderContext.draw_box"), reports.get(0));
        assertTrue(reports.get(1).contains("RenderContext.draw_model"), reports.get(1));
        assertTrue(reports.get(2).contains("RenderFrameContext.draw_item"), reports.get(2));
        assertTrue(reports.get(3).contains("RenderFrameContext.partial_tick"), reports.get(3));
        assertTrue(reports.get(4).contains("RenderFrameContext.camera_yaw"), reports.get(4));
        for (String report : reports) {
            assertTrue(report.contains("saved in a member variable"), report);
        }
        assertFalse(GrugRenderPass.isOpen(), "A refused call draws nothing.");
    }

    @Test
    void anIdThatIsNotAContextAtAllIsRefused() {
        GrugRenderPass.openFrame(0.0, 0.0);

        HostFunctions.RenderFrameContext_partial_tick(0);

        List<String> reports = drain();
        assertEquals(1, reports.size());
        assertTrue(reports.get(0).contains("RenderFrameContext.partial_tick"), reports.get(0));
        assertTrue(GrugRenderPass.closeFrame().items().isEmpty());
    }

    @Test
    void expiringAContextIdMakesItUnresolvableAndAnUnknownIdIsHarmless() {
        GrugRenderPass.open();
        long context = contextId();

        GrugEntities.removeEntity(context);
        assertEquals(
                null, GrugEntities.entityData.get(context), "A spent context no longer resolves.");

        // The render pass' own cleanup can race a context the engine already removed, so removing
        // an
        // id that was never there is a no-op rather than an error.
        GrugEntities.removeEntity(0);
        assertTrue(GrugRenderPass.close().isEmpty());
    }
}
