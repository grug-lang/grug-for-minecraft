package net.grug.minecraft.core;

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/** Covers the LICENSE gate in {@link GrugCore#initialize}. */
class GrugCoreTest {

    @TempDir Path mods;

    @Test
    void initializeRejectsAModWithoutALicense() throws Exception {
        Files.createDirectories(mods.resolve("mymod"));
        assertThrows(
                IllegalStateException.class,
                () -> GrugCore.initialize(null, new File("mod_api.json"), mods.toFile()));
    }
}
