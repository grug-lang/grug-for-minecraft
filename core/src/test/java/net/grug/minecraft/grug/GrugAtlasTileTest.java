package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class GrugAtlasTileTest {
    @Test
    void texelEdgesAreTheReferencesSheetCoordinates() {
        // Tile index 31 is column 15, row 1: the reference's u for texel 3 of it is 243 / 256.
        assertEquals(243.0 / 256.0, GrugAtlasTile.u(3.0 / 16.0, 0.0), 0.0);
        assertEquals(19.0 / 256.0, GrugAtlasTile.v(3.0 / 16.0, 0.0), 0.0);
    }

    @Test
    void theInsetIsInTexelsOfTheSheet() {
        assertEquals((243.0 - 0.01) / 256.0, GrugAtlasTile.u(3.0 / 16.0, 0.01), 0.0);
        assertEquals((19.0 - 0.01) / 256.0, GrugAtlasTile.v(3.0 / 16.0, 0.01), 0.0);
    }

    @Test
    void aWholeTextureSpansExactlyOneTile() {
        assertEquals(240.0 / 256.0, GrugAtlasTile.u(0.0, 0.0), 0.0);
        assertEquals(1.0, GrugAtlasTile.u(1.0, 0.0), 0.0);
        assertEquals(16.0 / 256.0, GrugAtlasTile.v(0.0, 0.0), 0.0);
        assertEquals(32.0 / 256.0, GrugAtlasTile.v(1.0, 0.0), 0.0);
    }
}
