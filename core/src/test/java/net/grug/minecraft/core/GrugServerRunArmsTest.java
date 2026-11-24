package net.grug.minecraft.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

/** The decisions of a dedicated server's run loop, as pure functions. */
class GrugServerRunArmsTest {
    @Test
    void eitherHoldsWhenOneDoes() {
        assertTrue(GrugServerRunArms.either(true, false));
        assertTrue(GrugServerRunArms.either(false, true));
        assertFalse(GrugServerRunArms.either(false, false));
    }

    @Test
    void withPresentActsOnlyWhenBothAreThere() {
        List<String> acted = new ArrayList<>();

        GrugServerRunArms.withPresent("server", s -> s + "!", acted::add);
        GrugServerRunArms.withPresent((String) null, s -> "never", acted::add);
        GrugServerRunArms.withPresent("server", s -> (String) null, acted::add);

        assertEquals(List.of("server!"), acted);
    }

    @Test
    void aMovedHeightIsRoundedOnlyOnceARunHoldsOne() {
        assertEquals(70.5, GrugServerRunArms.movedHeld(64.0, 70.5000000004), 0.0);
        assertEquals(0.0, GrugServerRunArms.movedHeld(0.0, 70.5), 0.0);
    }

    @Test
    void aHeightIsRoundedToAMillionth() {
        assertEquals(71.0, GrugServerRunArms.roundedHeight(70.9999999), 0.0);
    }

    @Test
    void holdPicksTheHeightThenTheFloorThenNothing() {
        List<String> held = new ArrayList<>();

        // Well under the held height: the height wins even though the floor is also above them.
        GrugServerRunArms.hold(
                "p", 70.0, 60.0, 0.25, 65.0, p -> held.add("height"), (p, f) -> held.add("floor"));
        // Within the tolerance of the held height, but under the floor, which the arm is handed.
        GrugServerRunArms.hold(
                "p",
                70.0,
                69.9,
                0.25,
                70.5,
                p -> held.add("height"),
                (p, f) -> held.add("floor " + f));
        // No held height yet, under the floor.
        GrugServerRunArms.hold(
                "p", 0.0, 60.0, 0.25, 65.0, p -> held.add("height"), (p, f) -> held.add("floor"));
        // On the floor, at the held height.
        GrugServerRunArms.hold(
                "p", 70.0, 70.0, 0.25, 70.0, p -> held.add("height"), (p, f) -> held.add("floor"));

        assertEquals(List.of("height", "floor 70.5", "floor"), held);
    }

    @Test
    void guardHandsOverWhatATaskThrew() {
        List<String> failed = new ArrayList<>();

        GrugServerRunArms.guard(() -> {}, t -> failed.add("no"));
        GrugServerRunArms.guard(
                () -> {
                    throw new IllegalStateException("boom");
                },
                t -> failed.add(t.getMessage()));

        assertEquals(List.of("boom"), failed);
    }
}
