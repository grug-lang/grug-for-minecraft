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
 * Covers the methods a block's {@code init()} reaches through the context it is handed, and the
 * refusal of a context kept past its call, which a grug test cannot make: the entity that ran
 * {@code init()} is destroyed with it, so nothing is left to keep the context in.
 */
class HostFunctionsBlockInitContextTest {

    private static final String BLOCK = "grug:declared";

    @BeforeEach
    @AfterEach
    void clearState() {
        GrugDeclarations.closeInitContext();
        GrugEntities.entityData.clear();
        GrugEntities.entityFileIdsByName.clear();
        drain();
    }

    private static List<String> drain() {
        synchronized (GrugErrors.runtimeErrorQueue) {
            List<String> reports = new ArrayList<>(GrugErrors.runtimeErrorQueue);
            GrugErrors.runtimeErrorQueue.clear();
            return reports;
        }
    }

    private static long contextId(GrugBlockData block) {
        return GrugEntities.addEntity(
                GrugEntityType.BlockInitContext, GrugDeclarations.openInitContext(block));
    }

    @Test
    void theOpenContextDeclaresIntoItsBlock() {
        GrugBlockData block = new GrugBlockData(BLOCK);
        long context = contextId(block);
        GrugEntities.entityFileIdsByName.put("declared_entity", 1L);

        HostFunctions.BlockInitContext_set_hardness(context, 2.5);
        HostFunctions.BlockInitContext_set_material(context, "stone");
        HostFunctions.BlockInitContext_set_inventory_size(context, 9);
        HostFunctions.BlockInitContext_set_block_entity(context, "grug:declared_entity");
        HostFunctions.BlockInitContext_set_custom_render(context);
        HostFunctions.BlockInitContext_set_flat_lighting(context);

        assertEquals(2.5f, block.hardness);
        assertEquals("stone", block.material);
        assertEquals(9, block.inventorySize);
        assertEquals("grug:declared_entity", block.blockEntityString);
        assertTrue(block.customRender);
        assertTrue(block.flatLighting);
        assertTrue(drain().isEmpty(), "A legal call reports nothing.");
    }

    @Test
    void flatLightingBeforeCustomRenderIsReportedAndNotDeclared() {
        GrugBlockData block = new GrugBlockData(BLOCK);

        HostFunctions.BlockInitContext_set_flat_lighting(contextId(block));

        assertFalse(block.flatLighting, "Only custom geometry can be drawn flat.");
        List<String> reports = drain();
        assertEquals(1, reports.size(), "One report, for the author to fix.");
        assertTrue(reports.get(0).contains(BLOCK), "It names the block: " + reports.get(0));
        assertTrue(
                reports.get(0).contains("set_custom_render"),
                "It names the call that has to come first: " + reports.get(0));
    }

    @Test
    void aBlockEntityScriptThatDoesNotExistIsReportedAndNotDeclared() {
        GrugBlockData block = new GrugBlockData(BLOCK);

        HostFunctions.BlockInitContext_set_block_entity(contextId(block), "grug:missing");

        assertEquals(null, block.blockEntityString);
        List<String> reports = drain();
        assertEquals(1, reports.size(), "One report, for the author to fix.");
        assertTrue(reports.get(0).contains(BLOCK), "It names the block: " + reports.get(0));
        assertTrue(
                reports.get(0).contains("grug:missing"), "It names the script: " + reports.get(0));
    }

    @Test
    void aContextKeptPastItsCallIsRefusedByEveryMethod() {
        GrugBlockData block = new GrugBlockData(BLOCK);
        long context = contextId(block);
        GrugDeclarations.closeInitContext();

        HostFunctions.BlockInitContext_set_hardness(context, 2.5);
        HostFunctions.BlockInitContext_set_material(context, "wood");
        HostFunctions.BlockInitContext_set_inventory_size(context, 9);
        HostFunctions.BlockInitContext_set_block_entity(context, "grug:declared_entity");
        HostFunctions.BlockInitContext_set_custom_render(context);
        HostFunctions.BlockInitContext_set_flat_lighting(context);

        assertEquals(6, drain().size(), "Each refused call is reported.");
        assertEquals(new GrugBlockData(BLOCK).hardness, block.hardness);
        assertEquals(new GrugBlockData(BLOCK).material, block.material);
        assertFalse(block.customRender);
    }

    @Test
    void aContextFromAnEarlierCallIsRefusedWhileAnotherIsOpen() {
        GrugBlockData first = new GrugBlockData(BLOCK);
        long stale = contextId(first);
        GrugDeclarations.closeInitContext();
        GrugBlockData second = new GrugBlockData(BLOCK);
        contextId(second);

        HostFunctions.BlockInitContext_set_hardness(stale, 4);

        assertEquals(new GrugBlockData(BLOCK).hardness, first.hardness);
        assertEquals(new GrugBlockData(BLOCK).hardness, second.hardness);
        assertEquals(1, drain().size());
    }
}
