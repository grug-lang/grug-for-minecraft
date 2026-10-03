package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.grug.minecraft.core.ModLoaderAdapter;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

/** Covers how a mod-tree defect is reported. */
class GrugModTreeDefectTest {

    /** Everything the reporter queues for chat, drained before and after every test. */
    @BeforeEach
    @AfterEach
    void drainQueue() {
        synchronized (Grug.runtimeErrorQueue) {
            Grug.runtimeErrorQueue.clear();
        }
    }

    private static ModLoaderAdapter recordingAdapter(List<String> logged) {
        return (ModLoaderAdapter)
                Proxy.newProxyInstance(
                        GrugModTreeDefectTest.class.getClassLoader(),
                        new Class<?>[] {ModLoaderAdapter.class},
                        (proxy, method, args) -> {
                            if (method.getName().equals("logError")) {
                                logged.add(String.valueOf(args[0]));
                            }
                            return null;
                        });
    }

    @Test
    void reportsToTheLoaderThePlayerAndTheRun() {
        List<String> logged = new ArrayList<>();

        GrugModTreeDefect.reportToRunAndPlayer(
                "Recipe 'a/b.json' is broken.", recordingAdapter(logged));

        assertEquals(List.of("Recipe 'a/b.json' is broken."), logged);
        synchronized (Grug.runtimeErrorQueue) {
            assertEquals(
                    List.of("Recipe 'a/b.json' is broken."),
                    new ArrayList<>(Grug.runtimeErrorQueue));
        }
    }

    @Test
    void reportsBeforeAnyLoaderIsInstalled() {
        // The defects a mods tree carries are found during startup, which for some of them runs
        // before GrugCore has installed an adapter. That is no reason to swallow the report.
        GrugModTreeDefect.reportToRunAndPlayer("Something is wrong.", null);

        synchronized (Grug.runtimeErrorQueue) {
            assertTrue(Grug.runtimeErrorQueue.contains("Something is wrong."));
        }
    }

    @Test
    void reportReadsTheInstalledAdapter() {
        // The public entry point is what every loader calls, and it has to reach the same channels.
        GrugModTreeDefect.report("Something else is wrong.");

        synchronized (Grug.runtimeErrorQueue) {
            assertTrue(Grug.runtimeErrorQueue.contains("Something else is wrong."));
        }
    }
}
