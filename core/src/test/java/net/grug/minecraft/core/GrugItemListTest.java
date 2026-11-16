package net.grug.minecraft.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

/** Which items a player is listed: the operator tab's items only when they may use them (#892). */
class GrugItemListTest {
    @Test
    void operatorOnlyItemsAreDroppedAndTheOrderKept() {
        List<String> all = List.of("apple", "barrier", "stone", "command_block");
        assertEquals(
                List.of("apple", "stone"),
                GrugItemList.withoutOperatorOnly(all, Set.of("barrier", "command_block")));
    }

    @Test
    void anEmptyListAndAListWithNoOperatorItemsAreUnchanged() {
        assertEquals(List.of(), GrugItemList.withoutOperatorOnly(List.of(), Set.of("barrier")));
        assertEquals(
                List.of("apple"), GrugItemList.withoutOperatorOnly(List.of("apple"), Set.of()));
    }

    @Test
    void anOperatorGetsTheWholeListAndAnyoneElseTheFilteredOne() {
        List<String> all = List.of("apple", "barrier");
        List<String> filtered = List.of("apple");
        assertSame(all, GrugItemList.listedFor(true, all, filtered));
        assertSame(filtered, GrugItemList.listedFor(false, all, filtered));
    }

    @Test
    void theGameAndTheHostsOperatorListBothHaveToAgreeWhereTheListIsHeld() {
        assertTrue(GrugItemList.mayUseOperatorItems(true, true, () -> true));
        assertFalse(GrugItemList.mayUseOperatorItems(true, true, () -> false));
        assertFalse(GrugItemList.mayUseOperatorItems(false, true, () -> true));
        assertFalse(GrugItemList.mayUseOperatorItems(false, false, () -> true));
    }

    @Test
    void aJoinedClientAsksOnlyTheGameAndNeverTheList() {
        assertTrue(
                GrugItemList.mayUseOperatorItems(
                        true,
                        false,
                        () -> {
                            throw new AssertionError("a joined client holds no operator list");
                        }));
    }
}
