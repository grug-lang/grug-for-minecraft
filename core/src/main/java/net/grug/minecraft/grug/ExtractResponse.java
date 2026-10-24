package net.grug.minecraft.grug;

/** What a block entity's {@code extract_item} export answered a pipe asking to take an item. */
public final class ExtractResponse extends GrugResponse {
    public enum Kind {
        REFUSED,
        SLOT,
        CRAFTING_RESULT
    }

    private Kind kind = null;
    private int slot = 0;
    private int amount = 0;

    public ExtractResponse() {
        super("extract_item");
    }

    public void refuse() {
        if (beginAnswer("ExtractResponse.refuse")) {
            kind = Kind.REFUSED;
        }
    }

    public void giveFromSlot(double slot, double amount) {
        if (beginAnswer("ExtractResponse.give_from_slot")) {
            kind = Kind.SLOT;
            this.slot = (int) slot;
            this.amount = (int) amount;
        }
    }

    public void giveCraftingResult() {
        if (beginAnswer("ExtractResponse.give_crafting_result")) {
            kind = Kind.CRAFTING_RESULT;
        }
    }

    public Kind kind() {
        return kind;
    }

    public int slot() {
        return slot;
    }

    public int amount() {
        return amount;
    }
}
