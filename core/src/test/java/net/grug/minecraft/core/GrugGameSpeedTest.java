package net.grug.minecraft.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class GrugGameSpeedTest {
    @Test
    void aSpeedBelowOneStillTicksOnce() {
        assertEquals(1, GrugGameSpeed.wholeSteps(0.5));
        assertEquals(1, GrugGameSpeed.wholeSteps(0));
        assertEquals(1, GrugGameSpeed.wholeSteps(-3));
    }

    @Test
    void aWholeSpeedIsKeptAndAFractionIsCut() {
        assertEquals(1, GrugGameSpeed.wholeSteps(1));
        assertEquals(10, GrugGameSpeed.wholeSteps(10));
        assertEquals(2, GrugGameSpeed.wholeSteps(2.9));
    }
}
