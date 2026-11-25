package net.grug.minecraft.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.List;

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

    @Test
    void aBoxInsideOneSectionOwedThatWholeSection() {
        // The remesh a loader issues has to cover the section the box sits in, not only the box:
        // a face of the room is baked from cells the box leaves out.
        List<int[]> boxes = GrugRelitSections.sectionBoxes(2, 68, 2, 7, 74, 7);
        assertEquals(1, boxes.size());
        assertArrayEquals(new int[] {0, 64, 0, 15, 79, 15}, boxes.get(0));
    }

    @Test
    void aBoxOfTheRoomsSizeThatCrossesTheAxisOwedFourSections() {
        // A room built around the origin is the case a test's room is: -7 to 7 crosses x = 0 and
        // z = 0, so it reaches both sections on each axis even though it is 15 blocks wide.
        List<int[]> boxes = GrugRelitSections.sectionBoxes(-7, 68, -7, 7, 74, 7);
        assertEquals(4, boxes.size());
        assertArrayEquals(new int[] {-16, 64, -16, -1, 79, -1}, boxes.get(0));
        assertArrayEquals(new int[] {0, 64, -16, 15, 79, -1}, boxes.get(1));
        assertArrayEquals(new int[] {-16, 64, 0, -1, 79, 15}, boxes.get(2));
        assertArrayEquals(new int[] {0, 64, 0, 15, 79, 15}, boxes.get(3));
    }

    @Test
    void aBoxOnSectionBoundariesOwedExactlyTheSectionsItTouches() {
        // A box from 16 to 31 is section 1 alone, and one from 15 to 16 is sections 0 and 1: a
        // corner on a boundary belongs to the section it starts, not to the one before it.
        List<int[]> one = GrugRelitSections.sectionBoxes(16, 0, 16, 31, 15, 31);
        assertEquals(1, one.size());
        assertArrayEquals(new int[] {16, 0, 16, 31, 15, 31}, one.get(0));
        List<int[]> two = GrugRelitSections.sectionBoxes(15, 0, 0, 16, 0, 0);
        assertEquals(2, two.size());
        assertArrayEquals(new int[] {0, 0, 0, 15, 15, 15}, two.get(0));
        assertArrayEquals(new int[] {16, 0, 0, 31, 15, 15}, two.get(1));
    }

    @Test
    void aBoxAcrossSeveralSectionsOwedEachOfThemOnce() {
        // -20 to 20 covers sections -2, -1, 0 and 1 on x, and the whole of each on every axis.
        List<int[]> boxes = GrugRelitSections.sectionBoxes(-20, 0, 0, 20, 0, 0);
        assertEquals(4, boxes.size());
        assertArrayEquals(new int[] {-32, 0, 0, -17, 15, 15}, boxes.get(0));
        assertArrayEquals(new int[] {-16, 0, 0, -1, 15, 15}, boxes.get(1));
        assertArrayEquals(new int[] {0, 0, 0, 15, 15, 15}, boxes.get(2));
        assertArrayEquals(new int[] {16, 0, 0, 31, 15, 15}, boxes.get(3));
    }
}
