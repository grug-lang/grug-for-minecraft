package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * The list's scroll and viewport arithmetic, which is a pure function of what the frame knows so
 * every row a list draws is decided by a test rather than by where the wheel happened to land.
 */
class GrugListMathTest {

    @Test
    void aListThatFitsDoesNotScroll() {
        assertEquals(0.0, GrugListMath.maxScroll(2, 10.0, 50.0));
        assertEquals(0.0, GrugListMath.maxScroll(0, 10.0, 50.0));
        assertEquals(0.0, GrugListMath.maxScroll(5, 0.0, 50.0));
        assertEquals(0.0, GrugListMath.maxScroll(5, 10.0, 0.0));
        assertEquals(0.0, GrugListMath.maxScroll(-1, 10.0, 50.0));
    }

    @Test
    void theScrollStopsWhenTheLastRowReachesTheBottom() {
        assertEquals(50.0, GrugListMath.maxScroll(10, 10.0, 50.0));
        assertEquals(10.0, GrugListMath.maxScroll(6, 10.0, 50.0));
    }

    @Test
    void aScrollIsBroughtIntoRange() {
        assertEquals(0.0, GrugListMath.clampScroll(-5.0, 10, 10.0, 50.0));
        assertEquals(0.0, GrugListMath.clampScroll(Double.NaN, 10, 10.0, 50.0));
        assertEquals(25.0, GrugListMath.clampScroll(25.0, 10, 10.0, 50.0));
        assertEquals(50.0, GrugListMath.clampScroll(75.0, 10, 10.0, 50.0));
    }

    @Test
    void theWheelMovesTheScrollByAWholeRow() {
        assertEquals(10.0, GrugListMath.scrollBy(0.0, -1.0, 10, 10.0, 50.0));
        assertEquals(0.0, GrugListMath.scrollBy(10.0, 1.0, 10, 10.0, 50.0));
        assertEquals(5.0, GrugListMath.scrollBy(0.0, -0.5, 10, 10.0, 50.0));
        assertEquals(
                5.0,
                GrugListMath.scrollBy(5.0, 0.0, 10, 10.0, 50.0),
                "no movement keeps the scroll");
        assertEquals(
                50.0,
                GrugListMath.scrollBy(45.0, -100.0, 10, 10.0, 50.0),
                "a scroll past the end is clamped");
        assertEquals(
                0.0,
                GrugListMath.scrollBy(5.0, 100.0, 10, 10.0, 50.0),
                "a scroll above the start is clamped");
    }

    @Test
    void theViewportStartsAtTheRowTheScrollNames() {
        assertEquals(0, GrugListMath.firstVisible(0.0, 10, 10.0));
        assertEquals(0, GrugListMath.firstVisible(5.0, 10, 10.0));
        assertEquals(1, GrugListMath.firstVisible(10.0, 10, 10.0));
        assertEquals(0, GrugListMath.firstVisible(-5.0, 10, 10.0));
        assertEquals(0, GrugListMath.firstVisible(0.0, 0, 10.0));
        assertEquals(0, GrugListMath.firstVisible(0.0, 10, 0.0));
        assertEquals(10, GrugListMath.firstVisible(1000.0, 10, 10.0));
    }

    @Test
    void theViewportEndsAtTheLastRowItShows() {
        assertEquals(4, GrugListMath.lastVisible(0.0, 10, 10.0, 50.0));
        assertEquals(9, GrugListMath.lastVisible(50.0, 10, 10.0, 50.0));
        assertEquals(2, GrugListMath.lastVisible(0.0, 10, 10.0, 25.0), "a half row shows");
        assertEquals(-1, GrugListMath.lastVisible(0.0, 0, 10.0, 50.0));
        assertEquals(-1, GrugListMath.lastVisible(0.0, 10, 0.0, 50.0));
        assertEquals(-1, GrugListMath.lastVisible(0.0, 10, 10.0, 0.0));
        assertEquals(9, GrugListMath.lastVisible(1000.0, 10, 10.0, 50.0));
    }

    @Test
    void theVisibleCountIsWhatTheFrameAsksFor() {
        assertEquals(5, GrugListMath.visibleCount(0.0, 10, 10.0, 50.0));
        assertEquals(2, GrugListMath.visibleCount(0.0, 2, 10.0, 50.0));
        assertEquals(0, GrugListMath.visibleCount(0.0, 0, 10.0, 50.0));
    }

    @Test
    void thePointPicksTheRowItFallsIn() {
        assertEquals(0, GrugListMath.rowAt(0.0, 50.0, 0.0, 10, 10.0));
        assertEquals(0, GrugListMath.rowAt(9.9, 50.0, 0.0, 10, 10.0));
        assertEquals(1, GrugListMath.rowAt(10.0, 50.0, 0.0, 10, 10.0));
        assertEquals(2, GrugListMath.rowAt(5.0, 50.0, 15.0, 10, 10.0));
        assertEquals(-1, GrugListMath.rowAt(50.0, 50.0, 0.0, 10, 10.0));
        assertEquals(-1, GrugListMath.rowAt(-1.0, 50.0, 0.0, 10, 10.0));
        assertEquals(-1, GrugListMath.rowAt(0.0, 50.0, 0.0, 0, 10.0));
        assertEquals(-1, GrugListMath.rowAt(0.0, 50.0, 0.0, 10, 0.0));
        assertEquals(-1, GrugListMath.rowAt(Double.NaN, 50.0, 0.0, 10, 10.0));
        assertEquals(-1, GrugListMath.rowAt(45.0, 50.0, 100.0, 10, 10.0));
        assertEquals(-1, GrugListMath.rowAt(0.0, 0.0, 0.0, 10, 10.0), "a list of no height");
        assertEquals(-1, GrugListMath.rowAt(0.0, -5.0, 0.0, 10, 10.0), "above the list top");
        assertEquals(-1, GrugListMath.rowAt(0.0, 50.0, -15.0, 10, 10.0), "scrolled above row 0");
    }

    @Test
    void aRowIsDrawnBelowTheListTopByItsIndexMinusTheScroll() {
        assertEquals(0.0, GrugListMath.rowY(0, 0.0, 10.0));
        assertEquals(20.0, GrugListMath.rowY(2, 0.0, 10.0));
        assertEquals(10.0, GrugListMath.rowY(2, 10.0, 10.0));
        assertEquals(-5.0, GrugListMath.rowY(0, 5.0, 10.0));
    }

    @Test
    void aSelectionWhoseRowIsGoneIsDropped() {
        assertEquals(-1, GrugListMath.selectionAfterRowsChange(-1, 5));
        assertEquals(0, GrugListMath.selectionAfterRowsChange(0, 5));
        assertEquals(4, GrugListMath.selectionAfterRowsChange(4, 5));
        assertEquals(-1, GrugListMath.selectionAfterRowsChange(5, 5));
        assertEquals(-1, GrugListMath.selectionAfterRowsChange(0, 0));
    }
}
