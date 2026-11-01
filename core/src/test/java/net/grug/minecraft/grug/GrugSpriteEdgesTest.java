package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Covers holding a legacy block face's coordinate off the atlas boundary it stands exactly on.
 *
 * <p>The coordinates come from the game's own face renderer, so they are atlas coordinates from 0
 * to 1 over a 512-texel atlas: {@code (spriteX + minX * 16) / 512}. {@link #TEXEL} is one atlas
 * texel there, and the sprite below is the 16-texel one the stationapi chest face uses.
 */
class GrugSpriteEdgesTest {

    /** One atlas texel of StationAPI's 512-texel game atlas. */
    private static final double TEXEL = 1.0 / 512.0;

    /** The low edge of a 16-texel sprite at 144, as the chest's side sprite was measured at. */
    private static final float MIN = 144.0F / 512.0F;

    /** Its high edge. */
    private static final float MAX = 160.0F / 512.0F;

    @Test
    void aCoordinateStandingExactlyOnTheLowEdgeMovesHalfATexelIn() {
        assertEquals(MIN + 0.5 * TEXEL, GrugSpriteEdges.edgeCoordinate(MIN, MIN, MAX, 16), 1e-12);
    }

    @Test
    void aCoordinateStandingExactlyOnTheHighEdgeMovesHalfATexelIn() {
        // The rotation branches of the face renderer write this value, so a turned face reaches the
        // atlas at its high edge rather than its low one.
        assertEquals(MAX - 0.5 * TEXEL, GrugSpriteEdges.edgeCoordinate(MAX, MIN, MAX, 16), 1e-12);
    }

    @Test
    void theHundredthOfATexelInsideTheHighEdgeIsLeftAlone() {
        // What a full face writes at its high edge. A half-texel inset moved this one too, which
        // rescaled the whole face and moved 5243 pixels of redstone_engine_blue (#927).
        double inside = MAX - 0.01 * TEXEL;
        assertEquals(inside, GrugSpriteEdges.edgeCoordinate(inside, MIN, MAX, 16), 0.0);
    }

    @Test
    void anInteriorCoordinateIsLeftExactlyWhereItWas() {
        // A pipe's arm box runs from 0.25 to 1.0 of its block, so its sprite coordinates are a
        // sub-rectangle of the sprite and none of them stands on an edge.
        for (int texel = 1; texel < 16; texel++) {
            double inside = MIN + texel * TEXEL;
            assertEquals(inside, GrugSpriteEdges.edgeCoordinate(inside, MIN, MAX, 16), 0.0);
        }
    }

    @Test
    void aSpriteSpanningTheWholeAtlasUsesTheSameHalfTexel() {
        // A coordinate over a sprite from 0 to 1 is the other range the model path's UVs are not:
        // sixteen texels over the whole sheet, so half a texel is a thirty-second.
        assertEquals(0.5 / 16.0, GrugSpriteEdges.edgeCoordinate(0.0, 0.0F, 1.0F, 16), 1e-12);
        assertEquals(1.0 - 0.5 / 16.0, GrugSpriteEdges.edgeCoordinate(1.0, 0.0F, 1.0F, 16), 1e-12);
        assertEquals(0.5, GrugSpriteEdges.edgeCoordinate(0.5, 0.0F, 1.0F, 16), 0.0);
    }

    @Test
    void anInvertedFaceMovesOnlyTheCornerThatSitsOnAnEdge() {
        // A turned face runs from its high edge down to an interior coordinate; the edge corner
        // moves and the interior one does not.
        assertEquals(MAX - 0.5 * TEXEL, GrugSpriteEdges.edgeCoordinate(MAX, MIN, MAX, 16), 1e-12);
        double interior = MAX - 4.0 * TEXEL;
        assertEquals(interior, GrugSpriteEdges.edgeCoordinate(interior, MIN, MAX, 16), 0.0);
    }

    @Test
    void aSpriteOfOneTexelHoldsBothEdgesAtItsCentre() {
        float min = 176.0F / 512.0F;
        float max = 177.0F / 512.0F;
        assertEquals(min + 0.5 * TEXEL, GrugSpriteEdges.edgeCoordinate(min, min, max, 1), 1e-12);
        assertEquals(min + 0.5 * TEXEL, GrugSpriteEdges.edgeCoordinate(max, min, max, 1), 1e-12);
    }

    @Test
    void aSpriteOfAnyTexelCountUsesHalfOfItsOwnAtlasTexel() {
        // Thirty-two contents texels over thirty-two atlas texels are still one atlas texel each.
        float min = 64.0F / 512.0F;
        float max = 96.0F / 512.0F;
        assertEquals(min + 0.5 * TEXEL, GrugSpriteEdges.edgeCoordinate(min, min, max, 32), 1e-12);
        assertEquals(max - 0.5 * TEXEL, GrugSpriteEdges.edgeCoordinate(max, min, max, 32), 1e-12);
    }

    @Test
    void aCoordinateBeyondTheSpriteIsHeldInsideIt() {
        double past = MAX + 3.0 * TEXEL;
        assertEquals(MAX - 0.5 * TEXEL, GrugSpriteEdges.edgeCoordinate(past, MIN, MAX, 16), 1e-12);
        double before = MIN - 3.0 * TEXEL;
        assertEquals(
                MIN + 0.5 * TEXEL, GrugSpriteEdges.edgeCoordinate(before, MIN, MAX, 16), 1e-12);
    }

    @Test
    void aSpriteWithNoTexelsOrNoWidthReturnsTheCoordinate() {
        assertEquals(MAX, GrugSpriteEdges.edgeCoordinate(MAX, MIN, MAX, 0), 0.0);
        assertEquals(MIN, GrugSpriteEdges.edgeCoordinate(MIN, MIN, MIN, 16), 0.0);
    }

    @Test
    void aModelsZeroToSixteenCoordinateIsNotAnAtlasCoordinate() {
        // A model's UV is in 0 to 16 texels; this function takes atlas coordinates, so the caller
        // converts. Handing over a model coordinate would land outside every sprite and be held at
        // its edge, which is why the loader converts before calling rather than after.
        assertEquals(MAX - 0.5 * TEXEL, GrugSpriteEdges.edgeCoordinate(16.0, MIN, MAX, 16), 1e-12);
        assertEquals(MIN + 0.5 * TEXEL, GrugSpriteEdges.edgeCoordinate(0.0, MIN, MAX, 16), 1e-12);
    }
}
