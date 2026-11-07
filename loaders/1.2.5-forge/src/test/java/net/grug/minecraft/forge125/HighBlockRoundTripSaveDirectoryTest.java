package net.grug.minecraft.forge125;

import static org.junit.jupiter.api.Assertions.assertNull;

import net.minecraft.src.AnvilChunkLoader;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/**
 * What the game's own chunk loader answers from a save directory nothing has written yet (#1206).
 *
 * <p>{@code Test.high_block_round_trip} saves the test chunk into a directory of its own and waits
 * for the load to answer, which is how it learns that the queued save has reached its region file:
 * that only works while an empty directory answers no chunk at all. The loader would answer a
 * cached or default chunk instead if a future game change made one, and the round trip would then
 * read a chunk no save wrote.
 *
 * <p>The race this waits for cannot be forced from a unit test: {@code saveChunk} needs a live
 * world and a chunk and writes on the game's file IO thread, so the ordering is the game's, and the
 * coverage test under {@code mods/coverage} is what a CI run exercises. This pins the premise.
 */
class HighBlockRoundTripSaveDirectoryTest {

    @Test
    void anEmptySaveDirectoryHasNoChunkToLoad() throws IOException {
        File directory = Files.createTempDirectory("grug-high-block-round-trip").toFile();
        try {
            assertNull(
                    new AnvilChunkLoader(directory).loadChunk(null, 0, 0),
                    "a save directory nothing has written must not answer a chunk");
        } finally {
            delete(directory);
        }
    }

    private static void delete(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                delete(child);
            }
        }
        file.delete();
    }
}
