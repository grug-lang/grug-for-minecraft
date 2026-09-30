package net.grug.minecraft.grug;

public final class Color {
    private final int r;
    private final int g;
    private final int b;

    public Color(int r, int g, int b) {
        this.r = r;
        this.g = g;
        this.b = b;
    }

    public int r() {
        return r;
    }

    public int g() {
        return g;
    }

    public int b() {
        return b;
    }

    public int getRGB() {
        return (r << 16) | (g << 8) | b;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Color)) return false;
        Color other = (Color) o;
        return r == other.r && g == other.g && b == other.b;
    }

    @Override
    public int hashCode() {
        return (r << 16) | (g << 8) | b;
    }

    @Override
    public String toString() {
        return "Color[r=" + r + ", g=" + g + ", b=" + b + "]";
    }
}
