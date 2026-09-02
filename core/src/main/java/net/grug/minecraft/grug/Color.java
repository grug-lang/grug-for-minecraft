package net.grug.minecraft.grug;

public record Color(int r, int g, int b) {
    public int getRGB() {
        return (r << 16) | (g << 8) | b;
    }
}
