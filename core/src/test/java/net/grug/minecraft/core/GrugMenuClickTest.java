package net.grug.minecraft.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * What a merge click moves into the slot under the pointer (#1216).
 *
 * <p>Alpha applies a click to a grug menu on the server of a joined client, which only the
 * dispatch-only multiplayer job reaches, so the rule is a pure function of numbers and this test is
 * what pins it. The amount also decides whether the click writes the slot at all: the write is what
 * marks the block entity dirty and fires its {@code item_inserted} export, and a merge that moves
 * nothing must run neither.
 */
class GrugMenuClickTest {

    @Test
    void aLeftClickMovesTheWholeCarriedStack() {
        assertEquals(16, GrugMenuClick.mergeAmount(0, 16, 0, 64));
    }

    @Test
    void aLeftClickStopsAtTheRoomTheSlotHasLeft() {
        assertEquals(24, GrugMenuClick.mergeAmount(0, 64, 40, 64));
    }

    @Test
    void aLeftClickStopsAtTheCarriedStacksOwnMaximum() {
        assertEquals(8, GrugMenuClick.mergeAmount(0, 16, 8, 16));
    }

    @Test
    void aLeftClickOnAFullSlotMovesNothing() {
        assertEquals(0, GrugMenuClick.mergeAmount(0, 16, 64, 64));
    }

    @Test
    void aRightClickMovesOneItem() {
        assertEquals(1, GrugMenuClick.mergeAmount(1, 64, 0, 64));
    }

    @Test
    void aRightClickOnAFullSlotMovesNothing() {
        assertEquals(0, GrugMenuClick.mergeAmount(1, 64, 64, 64));
    }

    @Test
    void aRightClickWithNoRoomUnderTheStacksMaximumMovesNothing() {
        assertEquals(0, GrugMenuClick.mergeAmount(1, 16, 16, 16));
    }

    @Test
    void aSlotHoldingMoreThanItsStackMaximumMovesNothingRatherThanANegativeAmount() {
        assertEquals(0, GrugMenuClick.mergeAmount(0, 16, 65, 64));
    }
}
