package net.grug.minecraft.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Covers the name rules in {@link GrugRegistryNames}. */
class GrugRegistryNamesTest {

    @Test
    void aDeclaredNameMatchesByItsNamespaceOrExactly() {
        assertTrue(GrugRegistryNames.matches("grug:machine", "machine"));
        assertTrue(GrugRegistryNames.matches("machine", "machine"));
        assertFalse(GrugRegistryNames.matches("grug:other", "machine"));
        // The suffix is the namespace separator plus the whole path, not the path's tail.
        assertFalse(GrugRegistryNames.matches("notmachine", "machine"));
    }

    @Test
    void aScanKeepsTheMatchItAlreadyFound() {
        Object found = new Object();
        assertSame(
                found, GrugRegistryNames.firstItemMatch(found, new Object(), null, null, "", ""));
        assertSame(
                found,
                GrugRegistryNames.firstItemMatch(found, null, "own", "stack", "item.x", "tile.x"));
    }

    @Test
    void anEmptySlotMatchesNothing() {
        assertNull(
                GrugRegistryNames.firstItemMatch(null, null, "own", "stack", "item.x", "tile.x"));
    }

    @Test
    void eachSpellingOfTheItemNameMatches() {
        Object item = new Object();
        assertSame(
                item,
                GrugRegistryNames.firstItemMatch(
                        null, item, "item.x", "stack", "item.x", "tile.x"));
        assertSame(
                item,
                GrugRegistryNames.firstItemMatch(
                        null, item, "tile.x", "stack", "item.x", "tile.x"));
        assertSame(
                item,
                GrugRegistryNames.firstItemMatch(null, item, "own", "item.x", "item.x", "tile.x"));
        assertSame(
                item,
                GrugRegistryNames.firstItemMatch(null, item, "own", "tile.x", "item.x", "tile.x"));
    }

    @Test
    void aDifferentNameMatchesNothing() {
        assertNull(
                GrugRegistryNames.firstItemMatch(
                        null, new Object(), "own", "stack", "item.x", "tile.x"));
    }
}
