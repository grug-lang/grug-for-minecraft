package net.grug.minecraft.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import java.util.List;

/** The item list's search, which follows the reference JEI's filter (#865). */
class GrugItemSearchTest {
    private static final String[] NAMES = {
        "stone", "iron ingot", "gold ingot", "stone pickaxe", "redstone engine", "grug item"
    };
    private static final String[] MODS = {
        "minecraft", "minecraft", "minecraft", "minecraft", "grug", "grug"
    };

    private static List<Integer> search(String text) {
        return GrugItemSearch.search(
                text,
                new GrugItemSearch.Names() {
                    @Override
                    public int count() {
                        return NAMES.length;
                    }

                    @Override
                    public String name(int index) {
                        return NAMES[index];
                    }

                    @Override
                    public String modId(int index) {
                        return MODS[index];
                    }
                });
    }

    @Test
    void aWordIsASubstringOfTheNameInAnyCase() {
        assertEquals(List.of(0, 3, 4), search("StOnE"));
        assertEquals(List.of(1, 2), search("ingot"));
    }

    @Test
    void everyWordOfAnAlternativeMustMatch() {
        assertEquals(List.of(1), search("iron ingot"));
        assertEquals(List.of(1), search("ingot iron"));
        assertEquals(List.of(), search("iron pickaxe"));
    }

    @Test
    void anAlternativeAddsItsMatchesInListOrder() {
        assertEquals(List.of(1, 3), search("iron|pickaxe"));
        assertEquals(List.of(1, 3), search("pickaxe|iron"));
    }

    @Test
    void aMinusWordRemovesWhatItMatches() {
        assertEquals(List.of(0, 4), search("stone -pickaxe"));
        assertEquals(List.of(0, 3, 4), search("stone -xyz"));
        assertEquals(List.of(), search("xyz -stone"));
    }

    @Test
    void aTextWithNoWordToSearchListsEverything() {
        List<Integer> all = List.of(0, 1, 2, 3, 4, 5);
        assertEquals(all, search(""));
        assertEquals(all, search("   "));
        assertEquals(all, search("-stone"));
        assertEquals(all, search("@"));
        assertEquals(all, search("\"\""));
        assertEquals(all, search("|"));
    }

    @Test
    void aQuoteKeepsASpaceInsideOneWord() {
        assertEquals(List.of(1), search("\"iron ingot\""));
        assertEquals(List.of(), search("\"ingot iron\""));
        assertEquals(List.of(2), search("\"gold ingot"));
    }

    @Test
    void anAtWordSearchesTheModId() {
        assertEquals(List.of(4, 5), search("@grug"));
        assertEquals(List.of(4), search("@grug redstone"));
        assertEquals(List.of(0, 1, 2, 3), search("@mine"));
        assertEquals(List.of(0, 1, 2, 3), search("@minecraft|@mine"));
        assertEquals(List.of(), search("@grug -@grug"));
    }

    @Test
    void anotherPrefixIsPartOfTheWord() {
        assertEquals(List.of(), search("#stone"));
    }

    @Test
    void aModIdIsTheNamespaceOfARegistryName() {
        assertEquals("minecraft", GrugItemSearch.modIdOf("minecraft:stone"));
        assertEquals("grug", GrugItemSearch.modIdOf("Grug:engine"));
        assertEquals("minecraft", GrugItemSearch.modIdOf("vanilla:12"));
        assertEquals("minecraft", GrugItemSearch.modIdOf("item.pickaxeIron"));
        assertEquals("minecraft", GrugItemSearch.modIdOf(":odd"));
        assertEquals("minecraft", GrugItemSearch.modIdOf(null));
    }
}
