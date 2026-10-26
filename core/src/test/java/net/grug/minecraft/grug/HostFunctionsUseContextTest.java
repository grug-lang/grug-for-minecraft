package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

/** Covers the one method a block's {@code use()} reaches through the context it is handed. */
class HostFunctionsUseContextTest {

    @BeforeEach
    @AfterEach
    void clearState() {
        GrugBlockUse.closeContext();
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
    void theOpenContextDeclinesTheClick() {
        long context = GrugEntities.addEntity(GrugEntityType.UseContext, GrugBlockUse.openContext());

        HostFunctions.UseContext_decline_use(context);

        assertTrue(drain().isEmpty(), "A legal call reports nothing.");
    }

    @Test
    void aContextKeptPastItsCallIsRefused() {
        long context = GrugEntities.addEntity(GrugEntityType.UseContext, GrugBlockUse.openContext());
        GrugBlockUse.closeContext();

        HostFunctions.UseContext_decline_use(context);

        List<String> reports = drain();
        assertEquals(1, reports.size());
        assertTrue(reports.get(0).contains("UseContext.decline_use"), reports.get(0));
    }
}
