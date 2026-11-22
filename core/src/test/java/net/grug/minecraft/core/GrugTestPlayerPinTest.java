package net.grug.minecraft.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

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
        GrugTestPlayerPin.hold(314.5, 65.62, 148.5);
        GrugTestPlayerPin.Pose pose = GrugTestPlayerPin.target();
        assertNotNull(pose);
        assertEquals(314.5, pose.x);
        assertEquals(65.62, pose.y);
        assertEquals(148.5, pose.z);
        GrugTestPlayerPin.release();
        assertNull(GrugTestPlayerPin.target());
    }

    @Test
    void aSecondHoldMovesTheTarget() {
        GrugTestPlayerPin.hold(1, 2, 3);
        GrugTestPlayerPin.hold(4, 5, 6);
        assertEquals(4, GrugTestPlayerPin.target().x);
    }
}
