package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Covers the fallback a loader asks for when it cannot resolve a vanilla block name. */
class GrugBlockNamesTest {

    /** The blocks a fake loader has, which is every name but the lit torch's other spelling. */
    private static Map<String, String> thisLoaderHas() {
        Map<String, String> blocks = new HashMap<>();
        blocks.put("redstone_torch", "lit torch");
        blocks.put("stone", "stone");
        return blocks;
    }

    private static Function<String, String> lookup(Map<String, String> blocks, List<String> asked) {
        return path -> {
            asked.add(path);
            return blocks.get(path);
        };
    }

    @Test
    void returnsWhatTheLoaderAlreadyHas() {
        List<String> asked = new ArrayList<>();

        assertEquals(
                "lit torch",
                GrugBlockNames.resolve("redstone_torch", lookup(thisLoaderHas(), asked)));
        assertEquals(List.of("redstone_torch"), asked);
    }

    @Test
    void triesTheOtherSpellingWhenThisLoaderDoesNotHaveTheName() {
        List<String> asked = new ArrayList<>();

        assertEquals(
                "lit torch",
                GrugBlockNames.resolve("redstone_torch_lit", lookup(thisLoaderHas(), asked)));
        assertEquals(List.of("redstone_torch_lit", "redstone_torch"), asked);
    }

    @Test
    void resolvesNothingForANameNoLoaderHas() {
        List<String> asked = new ArrayList<>();

        assertNull(GrugBlockNames.resolve("grug:does_not_exist", lookup(thisLoaderHas(), asked)));
        assertNull(GrugBlockNames.resolve("stone", lookup(new HashMap<>(), asked)));
    }

    @Test
    void triesTheOtherSpellingOnlyOnce() {
        // The lit torch is the only name with another spelling, so a name the fallback does not
        // rescue is asked once and answered with nothing.
        List<String> asked = new ArrayList<>();

        assertNull(GrugBlockNames.resolve("chest", lookup(thisLoaderHas(), asked)));
        assertEquals(List.of("chest"), asked);
    }

    @Test
    void holdsOnlyTheNamesAnAuditFoundToDiffer() {
        assertEquals(1, GrugBlockNames.otherSpellings().size());
        assertEquals("redstone_torch", GrugBlockNames.otherSpellings().get("redstone_torch_lit"));
    }

    @Test
    void handsOutTheTableReadOnly() {
        assertThrows(
                UnsupportedOperationException.class,
                () -> GrugBlockNames.otherSpellings().put("stone", "dirt"));
        assertTrue(GrugBlockNames.otherSpellings().containsKey("redstone_torch_lit"));
    }
}
