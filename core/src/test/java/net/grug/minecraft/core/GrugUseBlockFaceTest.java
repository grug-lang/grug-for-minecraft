package net.grug.minecraft.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.grug.minecraft.grug.Vec3;

import org.junit.jupiter.api.Test;

/**
 * Covers {@link GrugUseBlockFace}: the six faces a ray from the test player's eye can enter, and
 * the order that decides a ray into an edge.
 */
class GrugUseBlockFaceTest {

    /** An eye at the centre of a block whose own position is {@code (0, 0, 0)}. */
    private static final Vec3 EYE = new Vec3(0.5, 0.5, 0.5);

    @Test
    void aBlockBelowTheEyeIsClickedOnItsTop() {
        assertEquals(GrugUseBlockFace.UP, GrugUseBlockFace.face(EYE, 0, -1, 0));
    }

    @Test
    void aBlockAboveTheEyeIsClickedOnItsBottom() {
        assertEquals(GrugUseBlockFace.DOWN, GrugUseBlockFace.face(EYE, 0, 1, 0));
    }

    @Test
    void aBlockAtTheEyesLevelIsClickedOnTheSideFacingTheEye() {
        assertEquals(GrugUseBlockFace.WEST, GrugUseBlockFace.face(EYE, 1, 0, 0));
        assertEquals(GrugUseBlockFace.EAST, GrugUseBlockFace.face(EYE, -1, 0, 0));
        assertEquals(GrugUseBlockFace.NORTH, GrugUseBlockFace.face(EYE, 0, 0, 1));
        assertEquals(GrugUseBlockFace.SOUTH, GrugUseBlockFace.face(EYE, 0, 0, -1));
    }

    @Test
    void theAxisTheRayTravelsFurthestOnDecidesTheFace() {
        // Two blocks along one axis and one along another: the longer arm enters that face, even
        // when the shorter arm points at another one.
        assertEquals(GrugUseBlockFace.UP, GrugUseBlockFace.face(EYE, 1, -3, 0));
        assertEquals(GrugUseBlockFace.WEST, GrugUseBlockFace.face(EYE, 3, -1, 0));
    }

    @Test
    void aRayIntoAnEdgeOrCornerPicksTheSameFaceEveryTime() {
        // y is checked first, then x, then z, so a tie is not a coin toss.
        assertEquals(GrugUseBlockFace.UP, GrugUseBlockFace.face(EYE, 1, -1, 0));
        assertEquals(GrugUseBlockFace.UP, GrugUseBlockFace.face(EYE, 1, -1, 1));
        assertEquals(GrugUseBlockFace.WEST, GrugUseBlockFace.face(EYE, 1, 0, 1));
    }

    @Test
    void aRayFromTheBlocksOwnCentreStillAnswersAFace() {
        // The degenerate case the rule still has to answer: the eye is the centre, so no axis is
        // longer and the first one checked decides.
        assertEquals(GrugUseBlockFace.UP, GrugUseBlockFace.face(EYE, 0, 0, 0));
    }
}
