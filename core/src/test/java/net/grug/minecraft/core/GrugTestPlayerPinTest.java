package net.grug.minecraft.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Covers {@link GrugTestPlayerPin}: a pose is held from a hold until a release. */
class GrugTestPlayerPinTest {
    @AfterEach
    void release() {
        GrugTestPlayerPin.release();
    }

    @Test
    void nothingIsHeldOutsideATest() {
        assertNull(GrugTestPlayerPin.target());
    }

    @Test
    void aHoldNamesThePoseUntilItIsReleased() {
        GrugTestPlayerPin.hold(314.5, 148.5);
        GrugTestPlayerPin.Pose pose = GrugTestPlayerPin.target();
        assertNotNull(pose);
        assertEquals(314.5, pose.x);
        assertEquals(148.5, pose.z);
        GrugTestPlayerPin.release();
        assertNull(GrugTestPlayerPin.target());
    }

    @Test
    void aSecondHoldMovesTheTarget() {
        GrugTestPlayerPin.hold(1, 3);
        GrugTestPlayerPin.hold(4, 6);
        assertEquals(4, GrugTestPlayerPin.target().x);
    }

    @Test
    void aRetargetMovesAHeldPoseAndHoldsNothingOtherwise() {
        GrugTestPlayerPin.retarget(7, 8);
        assertNull(GrugTestPlayerPin.target());
        GrugTestPlayerPin.hold(1, 3);
        GrugTestPlayerPin.retarget(7, 8);
        assertEquals(7, GrugTestPlayerPin.target().x);
        assertEquals(8, GrugTestPlayerPin.target().z);
    }

    @Test
    void aTickHoldsOnlyWhenAPoseIsHeldAndThereIsAPlayer() {
        assertNull(GrugTestPlayerPin.poseFor(true));
        GrugTestPlayerPin.hold(1, 3);
        assertNull(GrugTestPlayerPin.poseFor(false));
        assertEquals(1, GrugTestPlayerPin.poseFor(true).x);
    }

    @Test
    void aPlayerIsOffAHoldOnEitherAxisAndNeverOffNothing() {
        assertFalse(GrugTestPlayerPin.offHold(1, 3, 1, 3));
        assertTrue(GrugTestPlayerPin.offHold(1.5, 3, 1, 3));
        assertTrue(GrugTestPlayerPin.offHold(1, 3.5, 1, 3));
        assertFalse(GrugTestPlayerPin.offHold(1.5, 3.5, Double.NaN, Double.NaN));
    }
}
