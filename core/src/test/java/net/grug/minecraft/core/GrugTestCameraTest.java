package net.grug.minecraft.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.grug.minecraft.grug.Vec3;

import org.junit.jupiter.api.Test;

/** Covers the view rule in {@link GrugTestCamera}. */
class GrugTestCameraTest {

    /** A view a test can read and level, standing in for a loader's own fields. */
    private static final class View implements GrugTestCamera.View {
        private float yaw;
        private float pitch;
        private float previousYaw;
        private float previousPitch;
        private int levelCalls;

        @Override
        public float yaw(Object player) {
            return yaw;
        }

        @Override
        public float pitch(Object player) {
            return pitch;
        }

        @Override
        public float previousYaw(Object player) {
            return previousYaw;
        }

        @Override
        public float previousPitch(Object player) {
            return previousPitch;
        }

        @Override
        public void level(Object player) {
            yaw = 0.0F;
            pitch = 0.0F;
            previousYaw = 0.0F;
            previousPitch = 0.0F;
            levelCalls++;
        }
    }

    @Test
    void aViewIsMovedOnlyWhileTheRunOwnsItAndAnAngleIsOffLevel() {
        float[] offLevel = {3.0F, -7.0F, 180.0F, 45.0F};
        for (int mask = 0; mask < 16; mask++) {
            float yaw = (mask & 1) == 0 ? 0.0F : offLevel[0];
            float pitch = (mask & 2) == 0 ? 0.0F : offLevel[1];
            float previousYaw = (mask & 4) == 0 ? 0.0F : offLevel[2];
            float previousPitch = (mask & 8) == 0 ? 0.0F : offLevel[3];
            boolean anyOffLevel = mask != 0;
            for (boolean owned : new boolean[] {false, true}) {
                for (boolean present : new boolean[] {false, true}) {
                    assertEquals(
                            owned && present && anyOffLevel,
                            GrugTestCamera.viewMoved(
                                    owned, present, yaw, pitch, previousYaw, previousPitch),
                            "owned=" + owned + " present=" + present + " mask=" + mask);
                }
            }
        }
    }

    @Test
    void aMissingPlayerHasNoViewToLevel() {
        View view = new View();
        view.yaw = 30.0F;

        assertFalse(GrugTestCamera.levelForFrame(true, null, view));
        assertEquals(30.0F, view.yaw);
        assertEquals(0, view.levelCalls);
    }

    @Test
    void aViewOwnedByTheRunIsLevelledAndAnUnmovedOneIsNotCounted() {
        View view = new View();
        view.yaw = 30.0F;
        view.pitch = -12.0F;

        assertFalse(GrugTestCamera.levelForFrame(false, new Object(), view));
        assertEquals(30.0F, view.yaw, "A view the runner does not own is left alone.");
        assertEquals(0, view.levelCalls);

        assertTrue(GrugTestCamera.levelForFrame(true, new Object(), view));
        assertEquals(0.0F, view.yaw);
        assertEquals(0.0F, view.pitch);
        assertEquals(1, view.levelCalls);
    }

    @Test
    void aLevelViewIsWrittenBackOnEveryFrame() {
        View view = new View();

        assertFalse(GrugTestCamera.levelForFrame(true, new Object(), view));
        assertEquals(
                1,
                view.levelCalls,
                "The level write runs on every frame of a run, so no coverage arm waits for a"
                        + " warp.");
    }

    @Test
    void aFrameIsBusyWithWorkLeftOrAMovedView() {
        View moved = new View();
        moved.yaw = 20.0F;
        View level = new View();

        assertTrue(GrugTestCamera.frameBusy(true, false, new Object(), level));
        assertTrue(GrugTestCamera.frameBusy(false, true, new Object(), moved));
        assertFalse(GrugTestCamera.frameBusy(false, true, new Object(), level));
    }

    @Test
    void aBusyFrameStillLevelsTheViewForTheNextOne() {
        View moved = new View();
        moved.pitch = 45.0F;

        assertTrue(GrugTestCamera.frameBusy(true, true, new Object(), moved));
        assertEquals(0.0F, moved.pitch);
        assertEquals(1, moved.levelCalls);
    }

    @Test
    void aCaptureWaitsForTheCameraToBeTheEyeItNames() {
        Vec3 eye = new Vec3(0.5, 65.62, -0.5);

        assertTrue(GrugTestCamera.cameraAt(eye, new Vec3(0.5, 65.62, -0.5)));
        // Within the tolerance the two processes' own arithmetic leaves.
        assertTrue(
                GrugTestCamera.cameraAt(
                        new Vec3(
                                0.5 + GrugTestCamera.CAPTURE_EYE_TOLERANCE,
                                65.62,
                                -0.5 - GrugTestCamera.CAPTURE_EYE_TOLERANCE),
                        eye));
        // A camera that has not arrived, on any of the three axes, is not the eye.
        for (Vec3 off :
                new Vec3[] {
                    new Vec3(0.5 + 0.01, 65.62, -0.5),
                    new Vec3(0.5, 65.62 + 0.01, -0.5),
                    new Vec3(0.5, 65.62, -0.5 + 0.01)
                }) {
            assertFalse(GrugTestCamera.cameraAt(off, eye));
        }
        // A side with no camera, or a request that named no eye, is never there.
        assertFalse(GrugTestCamera.cameraAt(null, eye));
        assertFalse(GrugTestCamera.cameraAt(eye, null));
    }

    @Test
    void aCaptureWaitsForTheFrameAndGivesTheCameraABoundedGrace() {
        // Under the minimum it always waits, whatever the other two say.
        assertTrue(GrugTestCamera.waitsForCapture(0, 60, 300, true, true));
        assertTrue(GrugTestCamera.waitsForCapture(59, 60, 300, false, false));
        // A frame that is not ready keeps the capture waiting to its own budget.
        assertTrue(GrugTestCamera.waitsForCapture(100, 60, 300, false, true));
        assertFalse(GrugTestCamera.waitsForCapture(300, 60, 300, false, true));
        // The camera has a grace past the minimum, and no longer.
        assertTrue(GrugTestCamera.waitsForCapture(100, 60, 300, true, false));
        assertFalse(
                GrugTestCamera.waitsForCapture(
                        60 + GrugTestCamera.EYE_GRACE_TICKS, 60, 300, true, false));
        // Ready and at the eye is a capture, and the grace never outlives the budget.
        assertFalse(GrugTestCamera.waitsForCapture(60, 60, 300, true, true));
        assertFalse(GrugTestCamera.waitsForCapture(60, 4, 60, true, false));
    }

    @Test
    void aCameraAxisIsOneComponentOfThePosition() {
        Vec3 camera = new Vec3(1.5, 65.62, -2.5);

        assertEquals(1.5, GrugTestCamera.axis(camera, 0), 0.0);
        assertEquals(65.62, GrugTestCamera.axis(camera, 1), 0.0);
        assertEquals(-2.5, GrugTestCamera.axis(camera, 2), 0.0);
        assertThrows(IllegalArgumentException.class, () -> GrugTestCamera.axis(camera, 3));
        IllegalStateException none =
                assertThrows(IllegalStateException.class, () -> GrugTestCamera.axis(null, 0));
        assertTrue(none.getMessage().contains("no test player"), none.getMessage());
    }
}
