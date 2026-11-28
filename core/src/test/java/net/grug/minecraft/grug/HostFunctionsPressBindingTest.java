package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.grug.minecraft.core.GrugCore;
import net.grug.minecraft.core.ModLoaderAdapter;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Covers pressing one declared binding by its id, which is how a test reaches a binding that a
 * declaration leaves unbound: the host registers it with the version's unbound key, so no letter or
 * digit press can, and the id is the only handle on it (#1868).
 *
 * <p>The press itself is the overlay path a real one takes, which a game test drives; this covers
 * the refusals and the bookkeeping around it, without a live grug state.
 */
class HostFunctionsPressBindingTest {

    @TempDir Path mods;

    private Object savedAdapter;

    @BeforeEach
    void installADeclaration() throws Exception {
        File modDir = mods.resolve("coverage").toFile();
        assertTrue(modDir.mkdirs());
        Files.write(
                new File(modDir, "about.json").toPath(),
                ("{\"keys\": [{\"id\": \"toggle\", \"name\": \"Toggle\", \"default_key\":"
                                + " \"B\"}, {\"id\": \"unbound\", \"name\": \"Unbound\"}]}")
                        .getBytes(StandardCharsets.UTF_8));
        clearMetadataCache();
        GrugOverlayScripts.resetForTest();
        Field field = GrugCore.class.getDeclaredField("adapter");
        field.setAccessible(true);
        savedAdapter = field.get(null);
        field.set(
                null,
                Proxy.newProxyInstance(
                        getClass().getClassLoader(),
                        new Class<?>[] {ModLoaderAdapter.class},
                        (proxy, method, args) -> {
                            if (method.isDefault()) {
                                return InvocationHandler.invokeDefault(proxy, method, args);
                            }
                            if (method.getName().equals("getGrugModsDirectory")) {
                                return mods.toFile();
                            }
                            if (method.getName().equals("logError")) return null;
                            throw new AssertionError(
                                    "a press binding test does not ask "
                                            + method.getName()
                                            + " of the adapter");
                        }));
    }

    @AfterEach
    void clearUp() throws Exception {
        GrugOverlayScripts.resetForTest();
        GrugTestState.currentTestMod = null;
        GrugTestContexts.close();
        Field field = GrugCore.class.getDeclaredField("adapter");
        field.setAccessible(true);
        field.set(null, savedAdapter);
        clearMetadataCache();
        drain();
    }

    private static void clearMetadataCache() throws Exception {
        Field field = GrugMetadata.class.getDeclaredField("cache");
        field.setAccessible(true);
        ((java.util.Map<?, ?>) field.get(null)).clear();
    }

    private static List<String> drain() {
        synchronized (GrugErrors.runtimeErrorQueue) {
            List<String> reports = new ArrayList<>(GrugErrors.runtimeErrorQueue);
            GrugErrors.runtimeErrorQueue.clear();
            return reports;
        }
    }

    @Test
    void aDeclaredBindingIsPressedByIdAndTheUnboundOneToo() {
        GrugTestState.currentTestMod = "coverage";
        long context = GrugTestContexts.tick();

        HostFunctions.TestTickContext_press_world_key_id(context, "toggle");
        HostFunctions.TestTickContext_press_world_key_id(context, "unbound");

        assertTrue(
                drain().isEmpty(),
                "A binding the declaration names is pressed whether or not it has a default key.");
    }

    @Test
    void anUndeclaredIdIsReportedAndTheDrawnMarkStays() {
        GrugWorldOverlay.noteDrawn();
        GrugTestState.currentTestMod = "coverage";
        long context = GrugTestContexts.tick();

        HostFunctions.TestTickContext_press_world_key_id(context, "no_such_binding");

        List<String> reports = drain();
        assertEquals(1, reports.size(), reports.toString());
        assertTrue(
                reports.get(0).contains("declares no binding named no_such_binding"),
                reports.get(0));
        assertTrue(
                GrugWorldOverlay.drawnForTest(),
                "A refused press is not a press, so it does not clear the drawn mark.");
        GrugWorldOverlay.clearDrawn();
    }

    @Test
    void aPressByIdWithoutATestModIsReported() {
        GrugTestState.currentTestMod = null;
        long context = GrugTestContexts.tick();

        HostFunctions.TestTickContext_press_world_key_id(context, "toggle");

        List<String> reports = drain();
        assertEquals(1, reports.size(), reports.toString());
        assertTrue(reports.get(0).contains("not one of a mod"), reports.get(0));
    }

    @Test
    void aPressByIdOutsideTheTickContextIsRefused() {
        GrugTestState.currentTestMod = "coverage";

        HostFunctions.TestTickContext_press_world_key_id(0, "toggle");

        List<String> reports = drain();
        assertEquals(1, reports.size(), reports.toString());
        assertTrue(reports.get(0).contains("TestTickContext.press_world_key_id"), reports.get(0));
        assertTrue(reports.get(0).contains("tick context is only valid"), reports.get(0));
    }
}
