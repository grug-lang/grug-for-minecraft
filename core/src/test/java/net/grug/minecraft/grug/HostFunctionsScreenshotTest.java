package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.grug.minecraft.core.GrugCore;
import net.grug.minecraft.core.ModLoaderAdapter;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Covers the capture {@code Screenshot.equals} takes before it compares (#1086): a capture the
 * adapter refused is already reported by the adapter, which its contract makes every null, so the
 * host function must not go on to read a reference directory and compare a null image, and must not
 * report a second error. See #1154.
 *
 * <p>No grug test can produce a refused capture on a healthy run, so the decision is driven from
 * Java with an adapter that answers null, the way {@code HostFunctionsStaleIdTest} drives its
 * guard.
 */
class HostFunctionsScreenshotTest {
    /** What the host function reported, in the order it reported it. */
    private final List<String> reports = new ArrayList<>();

    private Consumer<String> savedReport;
    private Object savedAdapter;

    @BeforeEach
    void installRecorder() throws Exception {
        GrugEntities.entityData.clear();
        GrugEntities.retainedFnEntities.clear();
        GrugEntities.fnEntities = GrugEntities.retainedFnEntities;
        savedReport = HostFunctions.staleIdReport;
        HostFunctions.staleIdReport = reports::add;
        savedAdapter = adapterField().get(null);
    }

    @AfterEach
    void restoreState() throws Exception {
        HostFunctions.staleIdReport = savedReport;
        adapterField().set(null, savedAdapter);
        GrugEntities.entityData.clear();
    }

    @Test
    void aRefusedCaptureIsNotCompared() throws Exception {
        adapterField()
                .set(
                        null,
                        proxy(
                                (proxy, method, args) -> {
                                    if (method.getName().equals("captureScreenshot")) {
                                        return null; // the adapter reported why, as a refusal does
                                    }
                                    Class<?> answer = method.getReturnType();
                                    if (answer == long.class) return 0L;
                                    if (answer == int.class) return 0;
                                    if (answer == double.class) return 0.0;
                                    if (answer == boolean.class) return false;
                                    return null;
                                }));
        long screenshot = GrugEntities.addEntity(GrugEntityType.Screenshot, new GrugScreenshot(0));

        // The null has to return before the mods directory is read or a reference is compared.
        HostFunctions.Screenshot_equals(screenshot, "screenshots/anything", 0, 0, 1, 1);

        assertEquals(List.of(), reports, "the adapter's own report is the only one");
    }

    private static Field adapterField() throws NoSuchFieldException {
        Field field = GrugCore.class.getDeclaredField("adapter");
        field.setAccessible(true);
        return field;
    }

    private ModLoaderAdapter proxy(InvocationHandler handler) {
        return (ModLoaderAdapter)
                Proxy.newProxyInstance(
                        getClass().getClassLoader(),
                        new Class<?>[] {ModLoaderAdapter.class},
                        handler);
    }
}
