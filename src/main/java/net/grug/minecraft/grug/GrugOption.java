package net.grug.minecraft.grug;

public record GrugOption(Object value) {
    public boolean is() {
        return value != null;
    }
}
