package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Covers {@link GameFunctionHelpers}' argument formatting and block-entity resolution. */
class GameFunctionHelpersTest {

    @AfterEach
    void restoreInitializingBlockEntity() {
        Grug.currentlyInitializingBlockEntity = null;
    }

    @Test
    void prettyFormatPrintsAPlainValue() {
        assertEquals("42.0", GameFunctionHelpers.prettyFormat(42.0));
    }

    @Test
    void prettyFormatUnwrapsAnEntityId() {
        long id = Grug.addEntity(GrugEntityType.Item, "diamond");
        assertEquals("diamond", GameFunctionHelpers.prettyFormat(id));
    }

    @Test
    void prettyFormatReportsAnUnknownId() {
        assertEquals("<id:987654321 (invalid)>", GameFunctionHelpers.prettyFormat(987654321L));
    }

    @Test
    void prettyFormatReportsAnEntityWithANullObject() {
        long id = Grug.addEntity(GrugEntityType.Item, null);
        assertEquals("<id:" + id + " (invalid)>", GameFunctionHelpers.prettyFormat(id));
    }

    @Test
    void resolveBlockEntityReturnsTheStoredObject() {
        Object blockEntity = new Object();
        long id = Grug.addEntity(GrugEntityType.BlockEntity, blockEntity);
        assertSame(blockEntity, GameFunctionHelpers.resolveBlockEntity(id));
    }

    @Test
    void resolveBlockEntityReturnsNullWhenUnknownAndNotInitializing() {
        assertNull(GameFunctionHelpers.resolveBlockEntity(555555L));
    }

    @Test
    void resolveBlockEntityUsesTheBlockEntityBeingInitialized() {
        Object initializing = new Object();
        Grug.currentlyInitializingBlockEntity = initializing;

        long id = 444444L;
        assertSame(initializing, GameFunctionHelpers.resolveBlockEntity(id));
        assertSame(initializing, Grug.entityData.get(id).object);
    }
}
