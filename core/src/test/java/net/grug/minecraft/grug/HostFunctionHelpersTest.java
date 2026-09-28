package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Covers {@link HostFunctionHelpers}' argument formatting and block-entity resolution. */
class HostFunctionHelpersTest {

    @AfterEach
    void restoreInitializingBlockEntity() {
        Grug.currentlyInitializingBlockEntity = null;
    }

    @Test
    void prettyFormatPrintsAPlainValue() {
        assertEquals("42.0", HostFunctionHelpers.prettyFormat(42.0));
    }

    @Test
    void prettyFormatUnwrapsAnEntityId() {
        long id = Grug.addEntity(GrugEntityType.Item, "diamond");
        assertEquals("diamond", HostFunctionHelpers.prettyFormat(id));
    }

    @Test
    void prettyFormatReportsAnUnknownId() {
        assertEquals("<id:987654321 (invalid)>", HostFunctionHelpers.prettyFormat(987654321L));
    }

    @Test
    void prettyFormatReportsAnEntityWithANullObject() {
        long id = Grug.addEntity(GrugEntityType.Item, null);
        assertEquals("<id:" + id + " (invalid)>", HostFunctionHelpers.prettyFormat(id));
    }

    @Test
    void resolveBlockEntityReturnsTheStoredObject() {
        Object blockEntity = new Object();
        long id = Grug.addEntity(GrugEntityType.BlockEntity, blockEntity);
        assertSame(blockEntity, HostFunctionHelpers.resolveBlockEntity(id));
    }

    @Test
    void resolveBlockEntityReturnsNullWhenUnknownAndNotInitializing() {
        assertNull(HostFunctionHelpers.resolveBlockEntity(555555L));
    }

    @Test
    void resolveBlockEntityUsesTheBlockEntityBeingInitialized() {
        Object initializing = new Object();
        Grug.currentlyInitializingBlockEntity = initializing;

        long id = 444444L;
        assertSame(initializing, HostFunctionHelpers.resolveBlockEntity(id));
        assertSame(initializing, Grug.entityData.get(id).object);
    }
}
