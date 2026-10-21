package net.grug.minecraft.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Arrays;

class GrugUnsupportedGameExceptionTest {
    @Test
    void namesEveryGameTheJarRunsOnAndSkipsBlankLines() {
        GrugForgeLayers.Resources jar = name -> new ByteArrayInputStream("A\n\nB\n".getBytes());
        assertEquals(Arrays.asList("A", "B"), GrugUnsupportedGameException.supported(jar));
        GrugUnsupportedGameException error = GrugUnsupportedGameException.of("X", jar);
        assertEquals(
                "grug does not run on X: remove it from the mods folder. It runs on: A, B.",
                error.getMessage());
        // What the loaders show, with no class name in front of the message.
        assertEquals(error.getMessage(), error.toString());
    }

    @Test
    void readsTheListTheJarHolds() {
        assertTrue(
                GrugUnsupportedGameException.of("X")
                        .getMessage()
                        .contains("runs on: Forge 1.20.6"));
    }

    @Test
    void aMissingListIsAnInvariant() {
        IllegalStateException error =
                assertThrows(
                        IllegalStateException.class,
                        () -> GrugUnsupportedGameException.supported(name -> null));
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
                        IllegalStateException.class,
                        () -> GrugUnsupportedGameException.supported(failing));
        assertTrue(error.getMessage().contains("Failed to read grug_supported_games.txt"));
    }
}
