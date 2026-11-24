package net.grug.minecraft.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.grug.minecraft.grug.Vec3;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * Covers the one line a run prints about its origin: the first answer prints it in a form {@code
 * run-multiplayer.sh} accepts as a real vector, and every later answer prints nothing (#1161).
 */
class GrugRunOriginTest {

    @Test
    void printsTheOriginOnceAndThenNothing() {
        ByteArrayOutputStream printed = new ByteArrayOutputStream();
        PrintStream original = System.out;
        System.setOut(new PrintStream(printed, true));
        try {
            GrugRunOrigin.reportOnce(new Vec3(0.5, 70.62, 52.5));
            GrugRunOrigin.reportOnce(new Vec3(9.5, 64.62, -3.5));
        } finally {
            System.setOut(original);
        }

        assertEquals(
                "[GRUG CI] Test origin is Vec3[x=0.5, y=70.62, z=52.5]." + System.lineSeparator(),
                new String(printed.toByteArray(), StandardCharsets.UTF_8));
    }
}
