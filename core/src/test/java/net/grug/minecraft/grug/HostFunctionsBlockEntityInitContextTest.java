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
 * Covers the methods a block entity's {@code init()} reaches through the context it is handed, and
 * the refusal of a context kept past its call. The grug side is covered by the coverage mod's
 * blocks, which declare in their inits; what a grug test cannot do is call with a context that is
 * no longer open, or with a limit that is not energy, since either would be an error in the init
 * that declares it.
 */
class HostFunctionsBlockEntityInitContextTest {

    private static final class Receiver implements GrugPower.Holder {
        @Override
        public void markEnergyChanged() {}
    }

    @BeforeEach
    @AfterEach
    void clearState() {
        GrugEntities.closeBlockEntityInitContext(null);
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

    private static long contextId(Object blockEntity) {
        return GrugEntities.addEntity(
                GrugEntityType.BlockEntityInitContext,
                GrugEntities.openBlockEntityInitContext(blockEntity));
    }

    @Test
    void theOpenContextDeclaresOnItsBlockEntity() {
        Receiver blockEntity = new Receiver();
        long context = contextId(blockEntity);

        HostFunctions.BlockEntityInitContext_set_power_receiver(context, 10);
        HostFunctions.BlockEntityInitContext_set_insertable_slots(context, 3, 4);
        HostFunctions.BlockEntityInitContext_set_extractable_slots(context, 5, 6);

        assertTrue(GrugPower.isReceiver(blockEntity));
        assertTrue(GrugSlotAccess.isInsertable(blockEntity, 3));
        assertFalse(GrugSlotAccess.isInsertable(blockEntity, 2));
        assertTrue(GrugSlotAccess.isExtractable(blockEntity, 5));
        assertFalse(GrugSlotAccess.isExtractable(blockEntity, 4));
        assertTrue(drain().isEmpty(), "A legal call reports nothing.");
    }

    @Test
    void aLimitThatIsNotEnergyIsReportedAndNotDeclared() {
        Receiver blockEntity = new Receiver();

        HostFunctions.BlockEntityInitContext_set_power_receiver(contextId(blockEntity), 2.5);

        assertFalse(GrugPower.isReceiver(blockEntity));
        List<String> reports = drain();
        assertEquals(1, reports.size(), "One report, for the author to fix.");
        assertTrue(reports.get(0).contains("set_power_receiver"), reports.get(0));
        assertTrue(reports.get(0).contains("2.5"), reports.get(0));
    }

    @Test
    void aContextKeptPastItsCallIsRefusedByEveryMethod() {
        Receiver blockEntity = new Receiver();
        long context = contextId(blockEntity);
        GrugEntities.closeBlockEntityInitContext(null);

        HostFunctions.BlockEntityInitContext_set_power_receiver(context, 10);
        HostFunctions.BlockEntityInitContext_set_insertable_slots(context, 3, 4);
        HostFunctions.BlockEntityInitContext_set_extractable_slots(context, 5, 6);

        assertEquals(3, drain().size(), "Each refused call is reported.");
        assertFalse(GrugPower.isReceiver(blockEntity));
        assertTrue(GrugSlotAccess.isInsertable(blockEntity, 2), "Nothing was declared.");
    }

    @Test
    void anEnclosingContextIsOpenAgainWhenTheInnerCallCloses() {
        Receiver outer = new Receiver();
        long outerContext = contextId(outer);
        GrugEntities.BlockEntityInitContext enclosing = GrugEntities.blockEntityInitContext();
        contextId(new Receiver());

        GrugEntities.closeBlockEntityInitContext(enclosing);
        HostFunctions.BlockEntityInitContext_set_power_receiver(outerContext, 10);

        assertTrue(GrugPower.isReceiver(outer));
        assertTrue(drain().isEmpty());
    }
}
