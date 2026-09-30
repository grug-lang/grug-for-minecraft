package net.grug.minecraft.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

/** Covers the GUI layout's slot-bounds validation. */
class GrugGuiBuilderTest {

    @Test
    void anEmptyLayoutHasNoOutOfBoundsSlot() {
        assertFalse(new GrugGuiBuilder("texture").firstSlotOutsideInventory(9).isPresent());
    }

    @Test
    void aGridInsideTheInventoryIsAccepted() {
        GrugGuiBuilder builder = new GrugGuiBuilder("texture");
        builder.craftingGrids.add(new GrugGuiBuilder.CraftingGridDef(0, 0, 0));
        assertFalse(builder.firstSlotOutsideInventory(9).isPresent());
    }

    @Test
    void aGridStartingBelowZeroIsReported() {
        GrugGuiBuilder builder = new GrugGuiBuilder("texture");
        builder.craftingGrids.add(new GrugGuiBuilder.CraftingGridDef(-1, 0, 0));
        assertEquals(-1, builder.firstSlotOutsideInventory(9).getAsInt());
    }

    @Test
    void aGridRunningPastTheInventoryIsReported() {
        GrugGuiBuilder builder = new GrugGuiBuilder("texture");
        builder.craftingGrids.add(new GrugGuiBuilder.CraftingGridDef(5, 0, 0));
        assertEquals(9, builder.firstSlotOutsideInventory(9).getAsInt());
    }

    @Test
    void aResultIsNotCheckedAgainstTheInventory() {
        // A result has no slot at all, so it cannot be out of bounds no matter how small the
        // inventory is.
        GrugGuiBuilder builder = new GrugGuiBuilder("texture");
        builder.craftingResults.add(new GrugGuiBuilder.CraftingResultDef(0, 0));
        assertFalse(builder.firstSlotOutsideInventory(0).isPresent());
    }

    @Test
    void theFirstOutOfBoundsGridWins() {
        GrugGuiBuilder builder = new GrugGuiBuilder("texture");
        builder.craftingGrids.add(new GrugGuiBuilder.CraftingGridDef(0, 0, 0));
        builder.craftingGrids.add(new GrugGuiBuilder.CraftingGridDef(8, 0, 0));
        assertEquals(9, builder.firstSlotOutsideInventory(9).getAsInt());
    }

    @Test
    void aResultDoesNotHideABadGrid() {
        GrugGuiBuilder builder = new GrugGuiBuilder("texture");
        builder.craftingGrids.add(new GrugGuiBuilder.CraftingGridDef(9, 0, 0));
        builder.craftingResults.add(new GrugGuiBuilder.CraftingResultDef(0, 0));
        assertEquals(9, builder.firstSlotOutsideInventory(9).getAsInt());
    }
}
