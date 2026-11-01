package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Covers the part of a block's shape that needs no game: whether a block is asked at all, and the
 * rule a box it declares has to follow. The box methods are what the context's two host functions
 * reach, so what a game has to cover is the call itself and the boxes reaching its own collision
 * and selection code.
 */
class GrugShapesTest {

    private static final long UNKNOWN_FILE_ID = 987654321L;
    private static final long UNKNOWN_SHAPE_FILE_ID = 987654322L;

    @BeforeEach
    @AfterEach
    void clearState() {
        GrugShapes.closeContext();
        GrugDeclarations.blockDataByFileId.remove(UNKNOWN_FILE_ID);
        GrugDeclarations.blockDataByFileId.remove(UNKNOWN_SHAPE_FILE_ID);
    }

    @Test
    void aFileIdThatIsNoBlockKeepsTheFullCube() {
        assertNull(GrugShapes.shape(UNKNOWN_FILE_ID, new Object(), 0, 0, 0));
    }

    @Test
    void aBlockThatNeverCalledSetShapeKeepsTheFullCube() {
        GrugDeclarations.blockDataByFileId.put(
                UNKNOWN_SHAPE_FILE_ID, new GrugBlockData("grug:coverage_shape_test"));

        assertNull(GrugShapes.shape(UNKNOWN_SHAPE_FILE_ID, new Object(), 0, 0, 0));
    }

    @Test
    void anOpenContextRecordsBothKindsOfBox() {
        GrugShapes.openContext();

        assertTrue(GrugShapes.recordCollision(new GrugBox(0.25, 0.25, 0.25, 0.75, 0.75, 0.75)));
        assertTrue(GrugShapes.recordSelection(new GrugBox(0.0, 0.0, 0.0, 1.0, 1.0, 1.0)));
    }

    @Test
    void aBoxWithItsCornersTheWrongWayRoundIsNotRecorded() {
        GrugShapes.openContext();

        assertFalse(GrugShapes.recordCollision(new GrugBox(1, 0, 0, 0, 1, 1)));
        assertFalse(GrugShapes.recordSelection(new GrugBox(0, 1, 1, 1, 0, 0)));
    }

    @Test
    void aBoxOutsideACallIsNotRecorded() {
        assertFalse(GrugShapes.recordCollision(new GrugBox(0, 0, 0, 1, 1, 1)));
        assertFalse(GrugShapes.recordSelection(new GrugBox(0, 0, 0, 1, 1, 1)));
    }
}
