package net.grug.minecraft.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.grug.minecraft.grug.Grug;

import org.junit.jupiter.api.Test;

class GrugStaleBlockEntityTest {
    @Test
    void aGrugBlockEntityUnderAnotherBlockIsStale() {
        assertTrue(GrugStaleBlockEntity.shouldDrop(true, false));
    }

    @Test
    void aGrugBlockEntityUnderAGrugBlockIsKept() {
        assertFalse(GrugStaleBlockEntity.shouldDrop(true, true));
    }

    @Test
    void anotherBlockEntityIsNeverDropped() {
        assertFalse(GrugStaleBlockEntity.shouldDrop(false, false));
        assertFalse(GrugStaleBlockEntity.shouldDrop(false, true));
    }

    @Test
    void nothingIsDroppedWhileAGrugBlockRemovesItself() {
        boolean[] during = new boolean[2];
        GrugStaleBlockEntity.duringRemoval(
                () -> {
                    during[0] = GrugStaleBlockEntity.shouldDrop(true, false);
                    GrugStaleBlockEntity.duringRemoval(() -> {});
                    during[1] = GrugStaleBlockEntity.shouldDrop(true, false);
                });

        assertFalse(during[0]);
        assertFalse(during[1], "an inner removal must not end the outer one");
        assertTrue(GrugStaleBlockEntity.shouldDrop(true, false));
    }

    @Test
    void aRemovalThatThrowsStillEndsTheWindow() {
        assertThrows(
                IllegalStateException.class,
                () ->
                        GrugStaleBlockEntity.duringRemoval(
                                () -> {
                                    throw new IllegalStateException("on_break failed");
                                }));

        assertTrue(GrugStaleBlockEntity.shouldDrop(true, false));
    }

    @Test
    void aGrugEntityMadeForAnotherBlockIsMadeAgain() {
        assertTrue(GrugStaleBlockEntity.shouldReinit(331L, 334L));
    }

    @Test
    void aGrugEntityOfTheBlockThatIsThereIsKept() {
        assertFalse(GrugStaleBlockEntity.shouldReinit(334L, 334L));
    }

    @Test
    void aBlockWithAnEntityFileIsInitedAfterOneWithNone() {
        assertTrue(
                GrugStaleBlockEntity.shouldReinit(Grug.INVALID_GRUG_FILE_ID, 334L),
                "a tile a block with no entity file initialized must be inited again for the"
                        + " chunk-mesh block placed on it");
    }

    @Test
    void anAttemptWithNoEntityFileIsNotRetriedForTheSameBlock() {
        assertFalse(
                GrugStaleBlockEntity.shouldReinit(
                        Grug.INVALID_GRUG_FILE_ID, Grug.INVALID_GRUG_FILE_ID));
    }

    @Test
    void aBlockWithNoEntityFileDropsTheEntityItFound() {
        assertTrue(
                GrugStaleBlockEntity.shouldReinit(334L, Grug.INVALID_GRUG_FILE_ID),
                "a block that names no entity file must not keep the previous block's script");
    }
}
