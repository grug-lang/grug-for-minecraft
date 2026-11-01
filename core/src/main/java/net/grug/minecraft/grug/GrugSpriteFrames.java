package net.grug.minecraft.grug;

/**
 * Where a baked model face's texture coordinate lands on an atlas sprite.
 *
 * <p>A model face's UVs run from 0 to 16, and UV 0 and UV 16 name the sprite's two outer edges.
 * Those edges are the boundaries between this sprite and whichever sprites the stitcher packed
 * beside it, and with nearest filtering the edge sample can fall on the neighbouring sprite's first
 * texel. That is #919: the stationapi atlas packed the fire sprite beside the chest sprite, and
 * fire regenerates its pixels with Math.random() every tick, so one pixel of the wooden pipe
 * capture was a different colour on every run.
 *
 * <p>Pulling the two outer edges a hundredth of a texel inside the sprite keeps every face sample
 * in its own sprite. Interior UVs are not moved: a model that takes a sub-rectangle of a larger
 * sheet, which is what the redstone engine's faces do, samples exactly the texels it named before.
 * That matters because a linear inset would pull those sub-rectangle edges onto the sheet's
 * padding, which is a visible change rather than a bleed being removed.
 *
 * <p>A legacy block face, which the game computes itself instead of baking from a model, needs more
 * than that. StationAPI's atlas minifies with {@code GL_LINEAR}, so a sample within half a texel of
 * the boundary blends in the sprite packed beside it; a hundredth of a texel still leaves half of
 * the neighbour in the footprint. {@link #clampToSprite} holds a legacy face to the centre of its
 * own edge texel, where the footprint spans that texel and nothing else. That is the remaining half
 * of #919, where the stationapi chest's face still took the fire sprite's pixels.
 */
public final class GrugSpriteFrames {

    @GrugGenerated("utility class: never instantiated")
    private GrugSpriteFrames() {}

    /** The highest model UV, which is the far edge of a face. */
    private static final double MODEL_UV_END = 16.0;

    /**
     * How far inside the sprite's edge a face sample is held, as a fraction of one texel. This is
     * the inset the vanilla block face renderer has always used on its high edge.
     */
    private static final float EDGE_INSET_TEXELS = 0.01F;

    /**
     * The same for a legacy block face, whose sprite may be minified with a linear filter: half a
     * texel is the centre of the edge texel, where the filter's footprint stops at the edge.
     */
    private static final float LEGACY_EDGE_INSET_TEXELS = 0.5F;

    /**
     * The texture coordinate a legacy block face may use, held to the centre of the sprite's edge
     * texel when the game's own face renderer puts it on or beyond the sprite's edge.
     *
     * <p>Unlike {@link #frameCoordinate}, {@code uv} is already an atlas coordinate: the face
     * renderer computes it from the sprite's position and the block's bounding box. An interior
     * coordinate is returned exactly, so a face only moves where it would otherwise sample the
     * neighbouring sprite.
     *
     * @param min the sprite's low edge, in atlas texture coordinates
     * @param max the sprite's high edge, in atlas texture coordinates
     * @param texels how many texels the sprite is across
     * @param uv the coordinate the face renderer computed
     */
    public static float clampToSprite(float min, float max, int texels, double uv) {
        if (texels <= 0 || max <= min) {
            return min;
        }
        float inset = LEGACY_EDGE_INSET_TEXELS * (max - min) / texels;
        if (uv < min + inset) {
            return min + inset;
        }
        if (uv > max - inset) {
            return max - inset;
        }
        return (float) uv;
    }

    /**
     * The texture coordinate {@code uv}, from 0 to 16, maps to on the sprite that spans {@code min}
     * to {@code max} and is {@code texels} texels across.
     *
     * @param min the sprite's low edge, in atlas texture coordinates
     * @param max the sprite's high edge, in atlas texture coordinates
     * @param texels how many texels the sprite is across
     * @param uv the model UV, from 0 at the sprite's low edge to 16 at its high edge
     */
    public static float frameCoordinate(float min, float max, int texels, double uv) {
        if (texels <= 0) {
            return min;
        }
        float span = max - min;
        float inset = EDGE_INSET_TEXELS * span / texels;
        float mapped = (float) (min + span * uv / MODEL_UV_END);
        if (mapped < min + inset) {
            return min + inset;
        }
        if (mapped > max - inset) {
            return max - inset;
        }
        return mapped;
    }
}
