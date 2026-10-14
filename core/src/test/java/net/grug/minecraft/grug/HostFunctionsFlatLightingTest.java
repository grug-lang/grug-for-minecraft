package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

/** Covers the one way set_flat_lighting is misused that a grug test cannot reach from run(). */
class HostFunctionsFlatLightingTest {

    private static final String BLOCK = "grug:flat_without_custom_render";

    @BeforeEach
    void startWithNothingReported() {
        drain();
    }

    @AfterEach
    void stopInitializingTheBlock() {
        GrugDeclarations.currentlyInitializingBlock = null;
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
    void flatLightingBeforeCustomRenderIsReportedAndNotDeclared() {
        GrugBlockData block = new GrugBlockData(BLOCK);
        GrugDeclarations.currentlyInitializingBlock = block;

        HostFunctions.set_flat_lighting();

        assertFalse(block.flatLighting, "Only custom geometry can be drawn flat.");
        List<String> reports = drain();
        assertEquals(1, reports.size(), "One report, for the author to fix.");
        assertTrue(reports.get(0).contains(BLOCK), "It names the block: " + reports.get(0));
        assertTrue(
                reports.get(0).contains("set_custom_render"),
                "It names the call that has to come first: " + reports.get(0));
    }

    @Test
    void flatLightingAfterCustomRenderIsDeclared() {
        GrugBlockData block = new GrugBlockData(BLOCK);
        block.customRender = true;
        GrugDeclarations.currentlyInitializingBlock = block;

        HostFunctions.set_flat_lighting();

        assertTrue(block.flatLighting);
        assertTrue(drain().isEmpty(), "Nothing to report when the order is right.");
    }
}
