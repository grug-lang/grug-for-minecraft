package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Covers the edge margin {@link GrugSpriteFrames} holds a baked model face's UVs inside its sprite.
 *
 * <p>The defect it exists for (#919) is that a face whose UV reaches 16 sampled the atlas sprite's
 * high edge exactly, which is the boundary with whatever sprite is packed beside it. On the
 * stationapi capture that neighbour turned out to be the fire sprite, which regenerates its pixels
 * with Math.random() every tick, so one pixel changed colour on every run.
 *
 * <p>These tests pin the three properties that fix it: UV 0 and UV 16 are held just inside the
 * sprite's own edges, and an interior UV maps exactly where it did before. The last one matters for
 * the models that take a sub-rectangle of a larger sheet, such as the redstone engine: moving their
 * inner edges would pull the sheet's padding into the face.
 */
class GrugSpriteFramesTest {

    /** A 16-texel sprite packed at x=144 in a 512-texel atlas, which is where chest_side sat. */
    private static final float MIN = 144.0F / 512.0F;

    private static final float MAX = 160.0F / 512.0F;

    /** One texel of that atlas, in texture coordinates. */
    private static final float TEXEL = 1.0F / 512.0F;

    @Test
    void theLowEdgeLandsJustInsideTheSprite() {
        assertEquals(
                MIN + 0.01F * TEXEL, GrugSpriteFrames.frameCoordinate(MIN, MAX, 16, 0.0), 1e-7);
    }

    @Test
    void theHighEdgeLandsJustInsideTheSprite() {
        assertEquals(
                MAX - 0.01F * TEXEL, GrugSpriteFrames.frameCoordinate(MIN, MAX, 16, 16.0), 1e-7);
    }

    @Test
    void bothEdgesStayInsideTheSprite() {
        float low = GrugSpriteFrames.frameCoordinate(MIN, MAX, 16, 0.0);
        float high = GrugSpriteFrames.frameCoordinate(MIN, MAX, 16, 16.0);
        assertTrue(low > MIN, "UV 0 is " + low + ", which is not above the sprite's edge " + MIN);
        assertTrue(
                high < MAX, "UV 16 is " + high + ", which is not below the sprite's edge " + MAX);
    }

    @Test
    void anInteriorUvMapsExactlyWhereItDidBefore() {
        assertEquals(
                MIN + (MAX - MIN) / 2.0F,
                GrugSpriteFrames.frameCoordinate(MIN, MAX, 16, 8.0),
                1e-7);
        assertEquals(
                MIN + (MAX - MIN) / 4.0F,
                GrugSpriteFrames.frameCoordinate(MIN, MAX, 16, 4.0),
                1e-7);
        assertEquals(
                MIN + (MAX - MIN) * 3.0F / 4.0F,
                GrugSpriteFrames.frameCoordinate(MIN, MAX, 16, 12.0),
                1e-7);
    }

    @Test
    void aWiderSpriteHoldsItsEdgeByTheSameFractionOfItsOwnTexel() {
        // One sprite texel is one atlas texel here, so a hundredth of it is the same atlas distance
        // whatever the sheet is, and the interior UV still maps exactly where it did.
        float min = 64.0F / 512.0F;
        float max = 96.0F / 512.0F;
        assertEquals(
                min + 0.01F * TEXEL, GrugSpriteFrames.frameCoordinate(min, max, 32, 0.0), 1e-7);
        assertEquals(
                max - 0.01F * TEXEL, GrugSpriteFrames.frameCoordinate(min, max, 32, 16.0), 1e-7);
        assertEquals(
                min + (max - min) / 2.0F,
                GrugSpriteFrames.frameCoordinate(min, max, 32, 8.0),
                1e-7);
    }

    @Test
    void aSpriteWithNoTexelsFallsBackToItsLowEdgeRatherThanDividingByZero() {
        assertEquals(MIN, GrugSpriteFrames.frameCoordinate(MIN, MAX, 0, 16.0), 0.0F);
    }

    @Test
    void aCoordinateAtEitherEdgeLandsOnTheCentreOfThatEdgeTexel() {
        assertEquals(MIN + 0.5F * TEXEL, GrugSpriteFrames.clampToSprite(MIN, MAX, 16, MIN), 1e-7);
        assertEquals(MAX - 0.5F * TEXEL, GrugSpriteFrames.clampToSprite(MIN, MAX, 16, MAX), 1e-7);
        // The high edge the game's face renderer actually computes is a hundredth of a texel
        // inside, and that still leaves half of the neighbouring sprite in a linear footprint.
        assertEquals(
                MAX - 0.5F * TEXEL,
                GrugSpriteFrames.clampToSprite(MIN, MAX, 16, MAX - 0.01F * TEXEL),
                1e-7);
    }

    @Test
    void aCoordinateBeyondEitherEdgeIsHeldAtTheSameCentre() {
        assertEquals(
                MIN + 0.5F * TEXEL,
                GrugSpriteFrames.clampToSprite(MIN, MAX, 16, MIN - 3.0F * TEXEL),
                1e-7);
        assertEquals(
                MAX - 0.5F * TEXEL,
                GrugSpriteFrames.clampToSprite(MIN, MAX, 16, MAX + 3.0F * TEXEL),
                1e-7);
    }

    @Test
    void anInteriorLegacyCoordinateIsReturnedExactly() {
        double[] interior = {
            MIN + 0.5 * TEXEL, MIN + TEXEL, (MIN + MAX) / 2.0, MAX - TEXEL, MAX - 0.5 * TEXEL
        };
        for (double uv : interior) {
            assertEquals((float) uv, GrugSpriteFrames.clampToSprite(MIN, MAX, 16, uv), 0.0F);
        }
    }

    @Test
    void aLegacySpriteOfAnyTexelCountUsesHalfOfItsOwnTexel() {
        float min = 64.0F / 512.0F;
        float max = 96.0F / 512.0F;
        // 32 contents texels over 32 atlas texels is still one atlas texel each, so half a texel
        // is the same atlas distance; a 16-texel sheet stretched over 32 atlas texels would have a
        // larger one, and the function follows the contents count it is given.
        assertEquals(min + 0.5F * TEXEL, GrugSpriteFrames.clampToSprite(min, max, 32, min), 1e-7);
        assertEquals(max - 0.5F * TEXEL, GrugSpriteFrames.clampToSprite(min, max, 32, max), 1e-7);
        // Sixteen contents texels stretched over twice as many atlas texels make one contents texel
        // two atlas texels, so half of it is one atlas texel, not half of one.
        assertEquals(min + TEXEL, GrugSpriteFrames.clampToSprite(min, max, 16, min), 1e-7);
        assertEquals(max - TEXEL, GrugSpriteFrames.clampToSprite(min, max, 16, max), 1e-7);
    }

    @Test
    void aLegacySpriteWithNoTexelsOrNoWidthFallsBackToItsLowEdge() {
        assertEquals(MIN, GrugSpriteFrames.clampToSprite(MIN, MAX, 0, MAX), 0.0F);
        assertEquals(MIN, GrugSpriteFrames.clampToSprite(MIN, MIN, 16, MIN), 0.0F);
    }
}
