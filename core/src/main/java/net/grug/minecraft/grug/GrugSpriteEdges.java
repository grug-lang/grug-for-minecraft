package net.grug.minecraft.grug;

/**
 * Where a legacy block face's texture coordinate sits when it is exactly on its sprite's edge.
 *
 * <p>This version computes a face's coordinates itself, from the sprite's position in the atlas and
 * the block's bounding box: {@code (spriteX + minX * 16) / atlasWidth} and {@code (spriteX + maxX *
 * 16 - 0.01) / atlasWidth}. The coordinate is therefore an atlas coordinate from 0 to 1, not the 0
 * to 16 a model's UVs use, and the low edge of a face that reaches its block's low corner is
 * exactly the sprite's own low edge. The rotation branches are the other case: they write {@code
 * (spriteX + 16 - minX * 16) / atlasWidth}, which for that same corner is exactly the sprite's high
 * edge. A box narrower than its block, such as a pipe's arm, gets a sub-rectangle of the sprite,
 * and a box wider than its block gets one reaching past it.
 *
 * <p>The atlas magnifies with {@code GL_NEAREST} and minifies with {@code GL_LINEAR}, so a
 * coordinate exactly on the edge maps to half a texel before the sprite's first texel column, which
 * is where the texel of the sprite packed beside it is picked up: nearest rounds back onto that
 * neighbour, and linear blends half of it in. On the single game atlas StationAPI packs every block
 * and item sprite into, the neighbour is whichever sprite the packer put there, and with the
 * vanilla content it is the fire sprite, whose pixels are regenerated every tick. That is the one
 * texel of {@code buildcraft/screenshots/wooden_pipe_straight} that changed between runs (#919).
 *
 * <p>Half an atlas texel in is the centre of the edge texel, where neither filter reaches the
 * neighbour. This class moves a coordinate only when it is exactly on an edge, and returns every
 * other coordinate unchanged: an interior coordinate names a texel the sprite holds, so moving it
 * would shift the face for nothing, and a sub-sprite face has no edge coordinate to move at all.
 */
public final class GrugSpriteEdges {

    /**
     * How close to an edge, in texels, still counts as on it. The face renderer quantises the
     * coordinates it writes to a hundredth of a texel ({@code 16 - 0.01}), so a thousandth tells an
     * edge from the hundredth-inside coordinate the high edge of a full face carries, and absorbs
     * the float rounding of a sprite bound that was divided on the sprite's side.
     */
    private static final double EDGE_TOLERANCE_TEXELS = 0.001;

    private GrugSpriteEdges() {}

    /**
     * The coordinate the face draws at: {@code uv} held half an atlas texel inside the sprite that
     * spans {@code min} to {@code max} and is {@code texels} texels across when it stands exactly
     * on one of that sprite's edges, and {@code uv} itself otherwise.
     *
     * @param uv the atlas coordinate the face renderer computed, from 0 to 1
     * @param min the sprite's low edge, in atlas coordinates
     * @param max the sprite's high edge, in atlas coordinates
     * @param texels how many texels the sprite is across, which is its contents' width or height
     */
    public static double edgeCoordinate(double uv, float min, float max, int texels) {
        if (texels <= 0 || !(max > min)) return uv;
        double texel = ((double) max - (double) min) / texels;
        double slack = EDGE_TOLERANCE_TEXELS * texel;
        if (uv <= (double) min + slack) return (double) min + 0.5 * texel;
        if (uv >= (double) max - slack) return (double) max - 0.5 * texel;
        return uv;
    }
}
