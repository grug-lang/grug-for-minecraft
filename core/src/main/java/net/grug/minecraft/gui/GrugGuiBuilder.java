package net.grug.minecraft.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

public class GrugGuiBuilder {
    public final String texturePath;
    public final List<SlotDef> blockSlots = new ArrayList<>();
    public final List<CraftingGridDef> craftingGrids = new ArrayList<>();
    public final List<CraftingResultDef> craftingResults = new ArrayList<>();
    public final List<TextDef> texts = new ArrayList<>();

    public int playerInvX, playerInvY, hotbarX, hotbarY;
    public boolean hasPlayerInventory = false;

    public GrugGuiBuilder(String texturePath) {
        this.texturePath = texturePath;
    }

    /** The first block inventory slot used by this GUI that is not in [0, inventorySize), if any. */
    public OptionalInt firstSlotOutsideInventory(int inventorySize) {
        for (SlotDef def : blockSlots) {
            if (isOutside(def.index(), inventorySize)) {
                return OptionalInt.of(def.index());
            }
        }
        for (CraftingGridDef grid : craftingGrids) {
            for (int i = 0; i < 9; i++) {
                if (isOutside(grid.startSlot() + i, inventorySize)) {
                    return OptionalInt.of(grid.startSlot() + i);
                }
            }
        }
        for (CraftingResultDef res : craftingResults) {
            if (isOutside(res.slot(), inventorySize)) {
                return OptionalInt.of(res.slot());
            }
        }
        return OptionalInt.empty();
    }

    private static boolean isOutside(int slot, int inventorySize) {
        return slot < 0 || slot >= inventorySize;
    }

    public record SlotDef(int index, int x, int y, boolean isOutput) {
    }

    public record CraftingGridDef(int startSlot, int x, int y) {
    }

    public record CraftingResultDef(int slot, int x, int y) {
    }

    public record TextDef(String text, int x, int y, int color) {
    }
}
