package net.grug.minecraft.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GrugRelitSectionsTest {

    @Test
    void nothingIsCoveredBeforeARoomIsRelit() {
        // The read a server makes before the first test's room has been built: a box that contains
        // nothing, at the origin and away from it.
        GrugRelitSections sections = new GrugRelitSections();
        assertFalse(sections.covers(0, 0, 0));
        assertFalse(sections.covers(-40, 70, 40));
    }

    @Test
    void aSectionTheBoxPartlyCoversIsCovered() {
        // A small room around the origin: its box is well inside section 0, so every position in
        // that section is a position the comparison cannot settle, not only the box itself.
        GrugRelitSections sections = new GrugRelitSections();
        sections.record(-7, 60, -7, 7, 74, 7);
        assertTrue(sections.covers(0, 68, 0));
        assertTrue(sections.covers(12, 68, 12));
        assertTrue(sections.covers(15, 79, 15));
        // One section away in each direction is outside what the sweep touched.
        assertFalse(sections.covers(16, 68, 0));
        assertFalse(sections.covers(0, 68, 16));
        assertFalse(sections.covers(0, 80, 0));
        assertFalse(sections.covers(0, 47, 0));
        assertFalse(sections.covers(0, 68, -17));
    }

    @Test
    void aBoxThatSpansTwoSectionsCoversBothAndNoMore() {
        GrugRelitSections sections = new GrugRelitSections();
        sections.record(-20, 0, 0, -10, 0, 0);
        // -20 falls in section -2 and -10 in section -1, so both are covered, including the cells
        // of those sections the box does not reach.
        assertTrue(sections.covers(-32, 0, 0));
        assertTrue(sections.covers(-17, 15, 15));
        assertTrue(sections.covers(-1, 0, 0));
        // A section on either side is not.
        assertFalse(sections.covers(-33, 0, 0));
        assertFalse(sections.covers(0, 0, 0));
        assertFalse(sections.covers(-20, 0, 16));
    }

    @Test
    void aLaterRoomReplacesTheEarlierBox() {
        GrugRelitSections sections = new GrugRelitSections();
        sections.record(0, 0, 0, 0, 0, 0);
        assertTrue(sections.covers(15, 15, 15));
        sections.record(100, 0, 0, 100, 0, 0);
        assertFalse(sections.covers(15, 15, 15));
        assertTrue(sections.covers(100, 0, 0));
    }
}
