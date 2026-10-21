package net.grug.minecraft.grug;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/** A catalogue of the mod remote that answers from memory, and an executor that waits to be run. */
final class FakeCatalogue {
    /** How many times the index was asked for. */
    int fetches;

    private final GrugCatalogue catalogue;

    /**
     * @param reachable whether the requests succeed
     * @param indexedHash the SHA-256 the index states for the zip
     */
    FakeCatalogue(byte[] zip, String indexedHash, boolean reachable) {
        String index =
                "{\"mods\":{\"remote\":{\"zip\":\"remote.zip\",\"size\":"
                        + zip.length
                        + ",\"sha256\":\""
                        + indexedHash
                        + "\"}}}";
        catalogue =
                new GrugCatalogue(
                        (url, limit, progress) -> {
                            if (!reachable) throw new IOException("offline");
                            if (url.endsWith("index.json")) {
                                fetches++;
                                return index.getBytes(StandardCharsets.UTF_8);
                            }
                            progress.accept(zip.length);
                            return zip;
                        },
                        "http://x/index.json");
    }

    GrugCatalogue catalogue() {
        return catalogue;
    }

    /** Holds the tasks it is given until a test runs them, as the game's own thread would not. */
    static final class Waiting implements Executor {
        private final List<Runnable> tasks = new ArrayList<>();

        @Override
        public void execute(Runnable task) {
            tasks.add(task);
        }

        void runAll() {
            List<Runnable> running = new ArrayList<>(tasks);
            tasks.clear();
            running.forEach(Runnable::run);
        }
    }
}
