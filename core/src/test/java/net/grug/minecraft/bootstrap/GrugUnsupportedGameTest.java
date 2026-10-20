package net.grug.minecraft.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Arrays;

class GrugUnsupportedGameTest {
    @Test
    void namesEveryGameTheJarRunsOnAndSkipsBlankLines() {
        GrugForgeLayers.Resources jar = name -> new ByteArrayInputStream("A\n\nB\n".getBytes());
        assertEquals(Arrays.asList("A", "B"), GrugUnsupportedGame.supported(jar));
        assertEquals(
                "grug has no layer for X, so it does nothing here. The grug jar runs on: A, B."
                        + " Remove it from this game's mods folder.",
                GrugUnsupportedGame.error("X", jar).getMessage());
    }

    @Test
    void readsTheListTheJarHolds() {
        assertTrue(GrugUnsupportedGame.error("X").getMessage().contains("runs on: Forge 1.20.6"));
    }

    @Test
    void aMissingListIsAnInvariant() {
        IllegalStateException error =
                assertThrows(
                        IllegalStateException.class,
                        () -> GrugUnsupportedGame.supported(name -> null));
        assertTrue(error.getMessage().contains("grug_supported_games.txt is missing"));
    }

    @Test
    void aListThatCannotBeReadIsAnInvariant() {
        GrugForgeLayers.Resources failing =
                name ->
                        new ByteArrayInputStream(new byte[0]) {
                            @Override
                            public void close() throws IOException {
                                throw new IOException("disk gone");
                            }
                        };
        IllegalStateException error =
                assertThrows(
                        IllegalStateException.class, () -> GrugUnsupportedGame.supported(failing));
        assertTrue(error.getMessage().contains("Failed to read grug_supported_games.txt"));
    }
}
