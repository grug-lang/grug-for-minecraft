package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Covers what a frame pass records for a liquid: its canonical name and the box to draw it in. */
class GrugLiquidDrawTest {

    @Test
    void aDrawCarriesTheLiquidAndItsBox() {
        GrugLiquidDraw draw =
                new GrugLiquidDraw("minecraft:water", 0.135, 0.0, 0.135, 0.865, 0.49, 0.865);

        assertEquals("minecraft:water", draw.liquid());
        assertEquals(0.135, draw.x1());
        assertEquals(0.0, draw.y1());
        assertEquals(0.135, draw.z1());
        assertEquals(0.865, draw.x2());
        assertEquals(0.49, draw.y2());
        assertEquals(0.865, draw.z2());
    }

    @Test
    void aDrawNamesItsLiquidInItsText() {
        assertEquals(
                "GrugLiquidDraw[minecraft:lava]",
                new GrugLiquidDraw("minecraft:lava", 0, 0, 0, 1, 1, 1).toString());
    }
}
