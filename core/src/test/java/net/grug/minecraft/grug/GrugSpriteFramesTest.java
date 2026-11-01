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
}
