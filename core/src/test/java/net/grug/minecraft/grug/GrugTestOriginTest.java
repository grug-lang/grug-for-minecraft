package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Covers the per-test origin cache that {@code TestTickContext.get_origin()} reads through. */
class GrugTestOriginTest {

    @AfterEach
    void clearTheCache() {
        GrugTestState.testOrigin = null;
        GrugTestState.movedEye = null;
    }

    @Test
    void capturesOnTheFirstCallAndReusesItAfterwards() {
        Vec3 first = new Vec3(1.0, 2.0, 3.0);
        Vec3 second = new Vec3(4.0, 5.0, 6.0);

        assertEquals(first, GrugTestState.captureTestOrigin(() -> first));
        assertEquals(first, GrugTestState.captureTestOrigin(() -> second));
    }

    @Test
    void theCaptureEyeIsTheRoomsEyeUntilThePlayerMovesAndThenWhereTheyWent() {
        Vec3 origin = new Vec3(10.0, 70.0, 20.0);
        Vec3 roomEye = new Vec3(10.0, 70.0 - 3.0, 20.0);

        assertEquals(roomEye.x(), GrugTestState.captureEye(() -> origin).x());
        assertEquals(roomEye.y(), GrugTestState.captureEye(() -> origin).y(), 1e-9);

        GrugTestState.playerMovedTo(11.5, 60.0, 20.0);

        Vec3 moved = GrugTestState.captureEye(() -> origin);
        assertEquals(11.5, moved.x());
        assertEquals(60.0 + 1.62, moved.y(), 1e-9);
        assertEquals(20.0, moved.z());
    }

    @Test
    void theCaptureEyeIsNullWhenThereIsNoPlayerToMeasureFrom() {
        org.junit.jupiter.api.Assertions.assertNull(GrugTestState.captureEye(() -> null));
    }
}
