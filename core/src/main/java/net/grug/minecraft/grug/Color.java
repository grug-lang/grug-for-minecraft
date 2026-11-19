package net.grug.minecraft.grug;

@GrugGenerated(
        "record-equivalent: JaCoCo filtered the generated members of the record this replaced")
public final class Color {
    private final int r;
    private final int g;
    private final int b;
    private final int a;

    public Color(int r, int g, int b) {
        this(r, g, b, 255);
    }

    public Color(int r, int g, int b, int a) {
        this.r = r;
        this.g = g;
        this.b = b;
        this.a = a;
    }

    /** How opaque the color is, 0 to 255. Only a translucent triangle reads it. */
    public int a() {
        return a;
    }

    /** The alpha in the top byte and the color below it. */
    public int getARGB() {
        return (a << 24) | getRGB();
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
        return r == other.r && g == other.g && b == other.b && a == other.a;
    }

    @Override
    public int hashCode() {
        return (r << 16) | (g << 8) | b;
    }

    @Override
    public String toString() {
        return "Color[r=" + r + ", g=" + g + ", b=" + b + (a == 255 ? "" : ", a=" + a) + "]";
    }
}
