package net.grug.minecraft.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
