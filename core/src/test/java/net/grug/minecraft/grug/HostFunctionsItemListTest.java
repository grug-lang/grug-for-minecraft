package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Covers the decisions behind the item list, the overlay's two draws and the synthetic press, which
 * a Java test can drive without the game's adapter: which arguments are refused, and what a loader
 * with no item list answers.
 */
class HostFunctionsItemListTest {

    @Test
    void aWholeIndexFromZeroUpIsAccepted() {
        assertNull(HostFunctions.itemIndexProblem(0));
        assertNull(HostFunctions.itemIndexProblem(7));
        assertNull(HostFunctions.itemIndexProblem(Integer.MAX_VALUE));
    }

    @Test
    void anIndexThatIsNegativeOrHasAFractionIsRefused() {
        assertTrue(HostFunctions.itemIndexProblem(-1).contains("whole number"));
        assertTrue(HostFunctions.itemIndexProblem(1.5).contains("whole number"));
        assertTrue(HostFunctions.itemIndexProblem(Double.NaN).contains("whole number"));
        assertTrue(HostFunctions.itemIndexProblem(1e12).contains("whole number"));
    }

    @Test
    void anIndexInsideTheListIsAcceptedAndOnePastItIsNot() {
        assertNull(HostFunctions.itemAtIndexProblem(0, 1));
        assertNull(HostFunctions.itemAtIndexProblem(9, 10));
        assertTrue(HostFunctions.itemAtIndexProblem(10, 10).contains("no item at index 10"));
        assertTrue(HostFunctions.itemAtIndexProblem(10, 10).contains("item_count is 10"));
    }

    @Test
    void aLoaderWithNoItemListIsReportedAsSuchRatherThanAsEmpty() {
        assertTrue(HostFunctions.itemAtIndexProblem(0, -1).contains("no item list"));
        assertTrue(HostFunctions.itemListMissing(-1));
        assertFalse(HostFunctions.itemListMissing(0));
        assertFalse(HostFunctions.itemListMissing(4000));
    }

    @Test
    void anOverlayIconPositionThatIsNotFiniteIsRefused() {
        assertNull(HostFunctions.overlayItemProblem(319, 1));
        assertTrue(HostFunctions.overlayItemProblem(Double.NaN, 1).contains("finite"));
        assertTrue(
                HostFunctions.overlayItemProblem(1, Double.NEGATIVE_INFINITY).contains("finite"));
    }

    @Test
    void anOverlayBoxTheWrongWayRoundIsRefusedAndOneThatWayRoundIsNot() {
        assertNull(HostFunctions.overlayBoxProblem(318, 0, 336, 18));
        assertNull(HostFunctions.overlayBoxProblem(318, 0, 318, 0));
        assertTrue(HostFunctions.overlayBoxProblem(10, 0, 5, 18).contains("right way round"));
        assertTrue(HostFunctions.overlayBoxProblem(0, 10, 18, 5).contains("right way round"));
    }

    @Test
    void anOverlayBoxCornerThatIsNotFiniteIsRefused() {
        // NaN compares false against both edges, so the wrong-way-round check alone would record
        // it, and the loader would cast it to an int; infinity would become the largest int.
        assertTrue(HostFunctions.overlayBoxProblem(Double.NaN, 0, 336, 18).contains("finite"));
        assertTrue(
                HostFunctions.overlayBoxProblem(318, 0, Double.POSITIVE_INFINITY, 18)
                        .contains("finite"));
        assertTrue(
                HostFunctions.overlayBoxProblem(318, 0, 336, Double.NEGATIVE_INFINITY)
                        .contains("finite"));
    }

    @Test
    void aPressWithFiniteArgumentsAndAWholeButtonIsAccepted() {
        assertNull(HostFunctions.screenClickProblem(327, 9, 0));
        assertNull(HostFunctions.screenClickProblem(0, 0, 2));
    }

    @Test
    void aPressWithANonFinitePositionOrABadButtonIsRefused() {
        assertTrue(HostFunctions.screenClickProblem(Double.NaN, 9, 0).contains("finite"));
        assertTrue(
                HostFunctions.screenClickProblem(1, Double.POSITIVE_INFINITY, 0)
                        .contains("finite"));
        assertTrue(HostFunctions.screenClickProblem(1, 2, 0.5).contains("whole number"));
        assertTrue(HostFunctions.screenClickProblem(1, 2, -1).contains("whole number"));
    }
}
