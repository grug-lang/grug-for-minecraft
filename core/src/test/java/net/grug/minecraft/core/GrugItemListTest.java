package net.grug.minecraft.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

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
}
