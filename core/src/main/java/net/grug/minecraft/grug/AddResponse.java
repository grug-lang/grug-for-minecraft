package net.grug.minecraft.grug;

/** What a block entity's {@code add_item} export answered a pipe asking to put an item in. */
public final class AddResponse extends GrugResponse {
    private boolean refused = false;
    private int slot = 0;

    public AddResponse() {
        super("add_item");
    }

    public void refuse() {
        if (beginAnswer("AddResponse.refuse")) {
            refused = true;
        }
    }

    public void acceptIntoSlot(double slot) {
        if (beginAnswer("AddResponse.accept_into_slot")) {
            this.slot = (int) slot;
        }
    }

    public boolean refused() {
        return refused;
    }

    public int slot() {
        return slot;
    }
}
