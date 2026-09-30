package net.grug.minecraft.gui;

import net.grug.minecraft.grug.GrugGenerated;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

public class GrugGuiBuilder {
    public final String texturePath;
    public final List<CraftingGridDef> craftingGrids = new ArrayList<>();
    public final List<CraftingResultDef> craftingResults = new ArrayList<>();
    public final List<TextDef> texts = new ArrayList<>();

    public int playerInvX, playerInvY, hotbarX, hotbarY;
    public boolean hasPlayerInventory = false;

    public GrugGuiBuilder(String texturePath) {
        this.texturePath = texturePath;
    }

    /**
     * The first block inventory slot used by this GUI that is not in [0, inventorySize), if any.
     */
    public OptionalInt firstSlotOutsideInventory(int inventorySize) {
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

    @GrugGenerated(
            "record-equivalent: JaCoCo filtered the generated members of the record this replaced")
    public static final class CraftingGridDef {
        private final int startSlot;
        private final int x;
        private final int y;

        public CraftingGridDef(int startSlot, int x, int y) {
            this.startSlot = startSlot;
            this.x = x;
            this.y = y;
        }

        public int startSlot() {
            return startSlot;
        }

        public int x() {
            return x;
        }

        public int y() {
            return y;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof CraftingGridDef)) return false;
            CraftingGridDef other = (CraftingGridDef) o;
            return startSlot == other.startSlot && x == other.x && y == other.y;
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(startSlot, x, y);
        }

        @Override
        public String toString() {
            return "CraftingGridDef[startSlot=" + startSlot + ", x=" + x + ", y=" + y + "]";
        }
    }

    @GrugGenerated(
            "record-equivalent: JaCoCo filtered the generated members of the record this replaced")
    public static final class CraftingResultDef {
        private final int slot;
        private final int x;
        private final int y;

        public CraftingResultDef(int slot, int x, int y) {
            this.slot = slot;
            this.x = x;
            this.y = y;
        }

        public int slot() {
            return slot;
        }

        public int x() {
            return x;
        }

        public int y() {
            return y;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof CraftingResultDef)) return false;
            CraftingResultDef other = (CraftingResultDef) o;
            return slot == other.slot && x == other.x && y == other.y;
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(slot, x, y);
        }

        @Override
        public String toString() {
            return "CraftingResultDef[slot=" + slot + ", x=" + x + ", y=" + y + "]";
        }
    }

    @GrugGenerated(
            "record-equivalent: JaCoCo filtered the generated members of the record this replaced")
    public static final class TextDef {
        private final String text;
        private final int x;
        private final int y;
        private final int color;

        public TextDef(String text, int x, int y, int color) {
            this.text = text;
            this.x = x;
            this.y = y;
            this.color = color;
        }

        public String text() {
            return text;
        }

        public int x() {
            return x;
        }

        public int y() {
            return y;
        }

        public int color() {
            return color;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof TextDef)) return false;
            TextDef other = (TextDef) o;
            return x == other.x
                    && y == other.y
                    && color == other.color
                    && java.util.Objects.equals(text, other.text);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(text, x, y, color);
        }

        @Override
        public String toString() {
            return "TextDef[text=" + text + ", x=" + x + ", y=" + y + ", color=" + color + "]";
        }
    }
}
