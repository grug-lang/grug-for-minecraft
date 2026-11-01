package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Covers the methods a block's shape export reaches through the context it is handed.
 *
 * <p>Like the render and test contexts, a shape context is only valid while the export it was
 * passed to is running, and a context saved and used later is refused with a report. The refusal is
 * a mod-tree defect, which a Java test can drive without a live grug state, so every method's
 * refusal is measured here; block_shape-Test.grug covers the boxes reaching the game.
 */
class HostFunctionsShapeContextTest {

    @BeforeEach
    @AfterEach
    void clearState() {
        GrugShapes.closeContext();
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
        return GrugEntities.addEntity(GrugEntityType.ShapeContext, GrugShapes.openContext());
    }

    @Test
    void theOpenContextRecordsBothKindsOfBox() {
        long context = contextId();

        HostFunctions.ShapeContext_add_collision_box(context, 0.25, 0.25, 0.25, 0.75, 0.75, 0.75);
        HostFunctions.ShapeContext_add_selection_box(context, 0.0, 0.0, 0.0, 1.0, 1.0, 1.0);

        assertTrue(drain().isEmpty(), "A legal box reports nothing.");
    }

    @Test
    void aContextSavedAfterItsCallIsRefusedByEveryMethod() {
        long context = contextId();
        GrugShapes.closeContext();

        HostFunctions.ShapeContext_add_collision_box(context, 0, 0, 0, 1, 1, 1);
        HostFunctions.ShapeContext_add_selection_box(context, 0, 0, 0, 1, 1, 1);

        List<String> reports = drain();
        assertEquals(2, reports.size(), "Every method names its own refusal.");
        assertTrue(reports.get(0).contains("ShapeContext.add_collision_box"), reports.get(0));
        assertTrue(reports.get(1).contains("ShapeContext.add_selection_box"), reports.get(1));
        for (String report : reports) {
            assertTrue(report.contains("saved in a member variable"), report);
        }
    }

    @Test
    void anIdThatIsNotAContextAtAllIsRefused() {
        contextId();

        HostFunctions.ShapeContext_add_collision_box(0, 0, 0, 0, 1, 1, 1);

        List<String> reports = drain();
        assertEquals(1, reports.size());
        assertTrue(reports.get(0).contains("ShapeContext.add_collision_box"), reports.get(0));
    }
}
