package net.grug.minecraft.grug;

/**
 * Where a 16 by 16 model texture sits on the 256 by 256 sheet a 1.2.5 loader uploads it on, and the
 * sheet coordinates of a point on it.
 *
 * <p>The reference draws its pipe textures from one such sheet, as a tile index, and computes a
 * face's coordinates as {@code (16 * column + texels) / 256}. Where a texel's edge lands on a
 * pixel's centre, which texel the pixel shows depends on the last bits of that float, so a face
 * drawn from a texture of its own with {@code texels / 16} can show the neighbouring texel. A
 * texture placed on a sheet at the reference's tile and given the reference's arithmetic shows the
 * same one.
 */
public final class GrugAtlasTile {
    /** The sheet is 16 tiles wide and high, each tile 16 texels. */
    public static final int SHEET = 256;

    public static final int TILE = 16;

    /** The tile column the reference's plain pipe texture, tile index 31, is in. */
    public static final int COLUMN = 15;

    /** The tile row the reference's plain pipe texture, tile index 31, is in. */
    public static final int ROW = 1;

    @GrugGenerated("utility class: never instantiated")
    private GrugAtlasTile() {}

    /**
     * The sheet's u for a point at {@code fraction} of the texture's width, pulled in by {@code
     * inset} texels the way the game's face methods pull in a face's larger edge.
     */
    public static double u(double fraction, double inset) {
        return (TILE * COLUMN + fraction * TILE - inset) / SHEET;
    }

    /** The sheet's v for a point at {@code fraction} of the texture's height, see {@link #u}. */
    public static double v(double fraction, double inset) {
        return (TILE * ROW + fraction * TILE - inset) / SHEET;
    }
}
