package net.grug.minecraft.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

class GrugLightSectionsTest {

    /** The bit of one offset, in the order the class documents. */
    private static int bit(int dx, int dy, int dz) {
        return 1 << ((dx + 1) * 9 + (dy + 1) * 3 + (dz + 1));
    }

    private static Set<String> offsets(int mask) {
        Set<String> offsets = new HashSet<>();
        for (int i = 0; i < 27; i++) {
            if ((mask & (1 << i)) != 0) {
                offsets.add(
                        GrugLightSections.offsetX(i)
                                + ","
                                + GrugLightSections.offsetY(i)
                                + ","
                                + GrugLightSections.offsetZ(i));
            }
        }
        return offsets;
    }

    @Test
    void anInteriorBlockReachesOnlyItsOwnSection() {
        // The block's light is read by the section it is in and by no section across a boundary it
        // does not touch.
        assertEquals(bit(0, 0, 0), GrugLightSections.maskForBlock(7, 7, 7));
        assertEquals(bit(0, 0, 0), GrugLightSections.maskForBlock(1, 14, 1));
    }

    @Test
    void aBlockOnAFaceBoundaryReachesTheSectionAcrossIt() {
        assertEquals(bit(-1, 0, 0) | bit(0, 0, 0), GrugLightSections.maskForBlock(0, 7, 7));
        assertEquals(bit(1, 0, 0) | bit(0, 0, 0), GrugLightSections.maskForBlock(15, 7, 7));
        assertEquals(bit(0, -1, 0) | bit(0, 0, 0), GrugLightSections.maskForBlock(7, 0, 7));
        assertEquals(bit(0, 1, 0) | bit(0, 0, 0), GrugLightSections.maskForBlock(7, 15, 7));
        assertEquals(bit(0, 0, -1) | bit(0, 0, 0), GrugLightSections.maskForBlock(7, 7, 0));
        assertEquals(bit(0, 0, 1) | bit(0, 0, 0), GrugLightSections.maskForBlock(7, 7, 15));
    }

    @Test
    void aBlockOnAnEdgeReachesTheFourSectionsAroundIt() {
        assertEquals(
                bit(-1, -1, 0) | bit(-1, 0, 0) | bit(0, -1, 0) | bit(0, 0, 0),
                GrugLightSections.maskForBlock(0, 0, 7));
        assertEquals(
                bit(1, 1, 0) | bit(1, 0, 0) | bit(0, 1, 0) | bit(0, 0, 0),
                GrugLightSections.maskForBlock(15, 15, 7));
        assertEquals(
                bit(-1, 0, 1) | bit(-1, 0, 0) | bit(0, 0, 1) | bit(0, 0, 0),
                GrugLightSections.maskForBlock(0, 7, 15));
        assertEquals(
                bit(0, 1, -1) | bit(0, 1, 0) | bit(0, 0, -1) | bit(0, 0, 0),
                GrugLightSections.maskForBlock(7, 15, 0));
    }

    @Test
    void aBlockOnACornerReachesTheEightSectionsAroundIt() {
        // The face vertices of a neighbouring section's block sample the corner block's light, so
        // all eight sections that meet at the corner can have a face whose vertex reads it.
        int expected = 0;
        for (int dx = -1; dx <= 0; dx++) {
            for (int dy = -1; dy <= 0; dy++) {
                for (int dz = -1; dz <= 0; dz++) {
                    expected |= bit(dx, dy, dz);
                }
            }
        }
        assertEquals(expected, GrugLightSections.maskForBlock(0, 0, 0));

        expected = 0;
        for (int dx = 0; dx <= 1; dx++) {
            for (int dy = 0; dy <= 1; dy++) {
                for (int dz = 0; dz <= 1; dz++) {
                    expected |= bit(dx, dy, dz);
                }
            }
        }
        assertEquals(expected, GrugLightSections.maskForBlock(15, 15, 15));
    }

    @Test
    void everyBlockPositionMarksTheSectionsItsLightCanReach() {
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    int mask = GrugLightSections.maskForBlock(x, y, z);
                    Set<String> actual = offsets(mask);
                    Set<String> expected = expectedOffsets(x, y, z);

                    assertTrue(
                            actual.contains("0,0,0"), "The block's own section is always marked");
                    assertEquals(expected, actual, "Block " + x + ", " + y + ", " + z);
                    assertEquals(expected.size(), Integer.bitCount(mask), "No duplicate offsets");
                }
            }
        }
    }

    @Test
    void aCoordinateOutsideASectionIsRefused() {
        assertThrows(
                IllegalArgumentException.class, () -> GrugLightSections.maskForBlock(-1, 0, 0));
        assertThrows(
                IllegalArgumentException.class, () -> GrugLightSections.maskForBlock(0, 16, 0));
        assertThrows(
                IllegalArgumentException.class, () -> GrugLightSections.maskForBlock(0, 0, 16));
    }

    /** The offsets the light rule gives, built independently of the mask's bit order. */
    private static Set<String> expectedOffsets(int x, int y, int z) {
        int[] xs = axisOffsets(x);
        int[] ys = axisOffsets(y);
        int[] zs = axisOffsets(z);
        Set<String> offsets = new HashSet<>();
        for (int dx : xs) {
            for (int dy : ys) {
                for (int dz : zs) {
                    offsets.add(dx + "," + dy + "," + dz);
                }
            }
        }
        return offsets;
    }

    private static int[] axisOffsets(int local) {
        if (local == 0) {
            return new int[] {-1, 0};
        }
        if (local == 15) {
            return new int[] {0, 1};
        }
        return new int[] {0};
    }
}
