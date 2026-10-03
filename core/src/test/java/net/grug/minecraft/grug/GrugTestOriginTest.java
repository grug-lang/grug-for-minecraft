package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Covers the per-test origin cache that {@code Test.get_origin()} reads through. */
class GrugTestOriginTest {

    @AfterEach
    void clearTheCache() {
        Grug.testOrigin = null;
    }

    @Test
    void capturesOnTheFirstCallAndReusesItAfterwards() {
        Vec3 first = new Vec3(1.0, 2.0, 3.0);
        Vec3 second = new Vec3(4.0, 5.0, 6.0);

        assertEquals(first, Grug.captureTestOrigin(() -> first));
        assertEquals(first, Grug.captureTestOrigin(() -> second));
    }
}
