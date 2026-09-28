package net.grug.minecraft.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void aResultInsideTheInventoryIsAccepted() {
        GrugGuiBuilder builder = new GrugGuiBuilder("texture");
        builder.craftingResults.add(new GrugGuiBuilder.CraftingResultDef(3, 0, 0));
        assertFalse(builder.firstSlotOutsideInventory(9).isPresent());
    }

    @Test
    void aResultPastTheInventoryIsReported() {
        GrugGuiBuilder builder = new GrugGuiBuilder("texture");
        builder.craftingResults.add(new GrugGuiBuilder.CraftingResultDef(10, 0, 0));
        assertEquals(10, builder.firstSlotOutsideInventory(9).getAsInt());
    }

    @Test
    void theFirstOutOfBoundsGridWins() {
        GrugGuiBuilder builder = new GrugGuiBuilder("texture");
        builder.craftingGrids.add(new GrugGuiBuilder.CraftingGridDef(0, 0, 0));
        builder.craftingGrids.add(new GrugGuiBuilder.CraftingGridDef(8, 0, 0));
        assertEquals(9, builder.firstSlotOutsideInventory(9).getAsInt());
    }

    @Test
    void aSlotExactlyAtTheInventorySizeIsOutside() {
        GrugGuiBuilder builder = new GrugGuiBuilder("texture");
        builder.craftingResults.add(new GrugGuiBuilder.CraftingResultDef(9, 0, 0));
        assertTrue(builder.firstSlotOutsideInventory(9).isPresent());
    }
}
