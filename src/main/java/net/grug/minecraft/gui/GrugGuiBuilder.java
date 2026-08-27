package net.grug.minecraft.gui;

import java.util.ArrayList;
import java.util.List;

public class GrugGuiBuilder {
    public final String texturePath;
    public final List<SlotDef> blockSlots = new ArrayList<>();
    public final List<CraftingGridDef> craftingGrids = new ArrayList<>();
    public final List<CraftingResultDef> craftingResults = new ArrayList<>();

    public int playerInvX, playerInvY, hotbarX, hotbarY;
    public boolean hasPlayerInventory = false;

    public GrugGuiBuilder(String texturePath) {
        this.texturePath = texturePath;
    }

    public record SlotDef(int index, int x, int y, boolean isOutput) {
    }

    public record CraftingGridDef(int startSlot, int x, int y) {
    }

    public record CraftingResultDef(int slot, int x, int y) {
    }
}
