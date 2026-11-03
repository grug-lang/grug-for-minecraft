package net.grug.minecraft.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
