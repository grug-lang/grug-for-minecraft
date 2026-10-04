package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Covers {@link GrugSlotAccess}: the permissive default, declared ranges, and the empty range.
 *
 * <p>The rules are process-wide and keyed by block entity, so every test declares on its own fresh
 * object rather than sharing one, which would let one test's rule answer another's.
 */
class GrugSlotAccessTest {

    @Test
    void anInventoryDeclaringNothingIsExtractableEverywhere() {
        Object blockEntity = new Object();
        assertTrue(GrugSlotAccess.isExtractable(blockEntity, 0));
        assertTrue(GrugSlotAccess.isExtractable(blockEntity, 8));
        assertTrue(GrugSlotAccess.isExtractable(blockEntity, 9));
    }

    @Test
    void aDeclaredRangeIsExtractableInclusiveAndNothingElse() {
        Object blockEntity = new Object();
        GrugSlotAccess.declareExtractable(blockEntity, 2, 4);
        assertFalse(GrugSlotAccess.isExtractable(blockEntity, 1));
        assertTrue(GrugSlotAccess.isExtractable(blockEntity, 2));
        assertTrue(GrugSlotAccess.isExtractable(blockEntity, 4));
        assertFalse(GrugSlotAccess.isExtractable(blockEntity, 5));
    }

    @Test
    void aLastBelowTheFirstDeclaresNoSlotAtAll() {
        Object blockEntity = new Object();
        GrugSlotAccess.declareExtractable(blockEntity, 3, 2);
        assertFalse(GrugSlotAccess.isExtractable(blockEntity, 3));
        assertFalse(GrugSlotAccess.isExtractable(blockEntity, 0));
    }

    @Test
    void declaringTheCraftingResultSlotGrantsOnlyIt() {
        // Slot 9 is the slot after a nine-slot inventory's last, which is where a crafting result
        // lives, so granting it means a machine's ingredients stay out of reach.
        Object blockEntity = new Object();
        GrugSlotAccess.declareExtractable(blockEntity, 9, 9);
        assertTrue(GrugSlotAccess.isExtractable(blockEntity, 9));
        assertFalse(GrugSlotAccess.isExtractable(blockEntity, 0));
        assertFalse(GrugSlotAccess.isExtractable(blockEntity, 8));
    }

    @Test
    void aRuleIsPerBlockEntity() {
        Object declared = new Object();
        Object undeclared = new Object();
        GrugSlotAccess.declareExtractable(declared, 1, 1);
        assertTrue(GrugSlotAccess.isExtractable(declared, 1));
        assertTrue(GrugSlotAccess.isExtractable(undeclared, 1));
    }

    @Test
    void declaringOneDirectionLeavesTheOtherUndeclared() {
        Object blockEntity = new Object();
        GrugSlotAccess.declareExtractable(blockEntity, 0, 0);
        assertTrue(GrugSlotAccess.isExtractable(blockEntity, 0));
        // Nothing was declared for insertion, so every insertable slot is offered.
        assertTrue(GrugSlotAccess.isInsertable(blockEntity, 0));
    }

    @Test
    void declaringTwiceReplacesTheEarlierRange() {
        Object blockEntity = new Object();
        GrugSlotAccess.declareExtractable(blockEntity, 0, 8);
        GrugSlotAccess.declareExtractable(blockEntity, 2, 2);
        assertFalse(GrugSlotAccess.isExtractable(blockEntity, 0));
        assertTrue(GrugSlotAccess.isExtractable(blockEntity, 2));
    }

    @Test
    void aFractionalSlotIsAnsweredAsTheSlotBelowIt() {
        // get_item_in_slot reads the slot below 0.5, so this must not claim a slot of half a pixel
        // is open when the caller will read slot 0.
        Object blockEntity = new Object();
        GrugSlotAccess.declareExtractable(blockEntity, 2, 4);
        assertTrue(GrugSlotAccess.isExtractable(blockEntity, 2.5));
        assertFalse(GrugSlotAccess.isExtractable(blockEntity, 1.5));

        Object insertable = new Object();
        GrugSlotAccess.declareInsertable(insertable, 2, 4);
        assertTrue(GrugSlotAccess.isInsertable(insertable, 4.9));
        assertFalse(GrugSlotAccess.isInsertable(insertable, 5.1));
    }

    @Test
    void anUnresolvedBlockEntityDeclaresNothing() {
        // HostFunctionHelpers.resolveBlockEntity answers null for an id that is not a block entity,
        // which is a block entity that has declared nothing like any other.
        assertTrue(GrugSlotAccess.isExtractable(null, 0));
        assertTrue(GrugSlotAccess.isInsertable(null, 0));
    }

    @Test
    void anUndeclaredDirectionIsFullyInsertable() {
        Object blockEntity = new Object();
        assertTrue(GrugSlotAccess.isInsertable(blockEntity, 0));
        assertTrue(GrugSlotAccess.isInsertable(blockEntity, 26));
    }

    @Test
    void aDeclaredRangeIsInsertableInclusiveAndNothingElse() {
        Object blockEntity = new Object();
        GrugSlotAccess.declareInsertable(blockEntity, 0, 8);
        assertTrue(GrugSlotAccess.isInsertable(blockEntity, 0));
        assertTrue(GrugSlotAccess.isInsertable(blockEntity, 8));
        assertFalse(GrugSlotAccess.isInsertable(blockEntity, 9));
    }

    @Test
    void aLastBelowTheFirstDeclaresNoInsertableSlot() {
        Object blockEntity = new Object();
        GrugSlotAccess.declareInsertable(blockEntity, 8, 0);
        assertFalse(GrugSlotAccess.isInsertable(blockEntity, 0));
        assertFalse(GrugSlotAccess.isInsertable(blockEntity, 8));
    }
}
