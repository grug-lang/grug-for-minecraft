package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

/**
 * Covers {@code TestTickContext.set_gui_scale} and {@code TestTickContext.press_game_key}: what
 * each accepts, what it refuses and what it hands the loader.
 */
class GrugGuiScaleAndGameKeyTest {
    private Object savedAdapter;
    private final List<Integer> scales = new ArrayList<>();
    private final List<Integer> keys = new ArrayList<>();

    @BeforeEach
    void installAdapter() throws Exception {
        Field field = GrugCore.class.getDeclaredField("adapter");
        field.setAccessible(true);
        savedAdapter = field.get(null);
        scales.clear();
        keys.clear();
        field.set(
                null,
                Proxy.newProxyInstance(
                        getClass().getClassLoader(),
                        new Class<?>[] {ModLoaderAdapter.class},
                        (proxy, method, args) -> {
                            if (method.getName().equals("setGuiScale")) {
                                scales.add((Integer) args[0]);
                                return null;
                            }
                            if (method.getName().equals("pressGameKey")) {
                                keys.add((Integer) args[0]);
                                return null;
                            }
                            if (method.isDefault()) {
                                return InvocationHandler.invokeDefault(proxy, method, args);
                            }
                            if (method.getName().equals("logError")) {
                                return null;
                            }
                            throw new AssertionError("does not ask " + method.getName());
                        }));
    }

    @AfterEach
    void restoreAdapter() throws Exception {
        Field field = GrugCore.class.getDeclaredField("adapter");
        field.setAccessible(true);
        field.set(null, savedAdapter);
        GrugTestContexts.close();
        drain();
        try {
            GrugErrors.throwPendingFatal();
        } catch (IllegalStateException expected) {
            // The fatal a test provoked, cleared so it cannot outlive the test.
        }
    }

    private static List<String> drain() {
        synchronized (GrugErrors.runtimeErrorQueue) {
            List<String> reports = new ArrayList<>(GrugErrors.runtimeErrorQueue);
            GrugErrors.runtimeErrorQueue.clear();
            return reports;
        }
    }

    @Test
    void aScaleFromOneToThreeIsHandedToTheLoader() {
        long context = GrugTestContexts.tick();

        HostFunctions.TestTickContext_set_gui_scale(context, 1);
        HostFunctions.TestTickContext_set_gui_scale(context, 2);
        HostFunctions.TestTickContext_set_gui_scale(context, 3);

        assertEquals(List.of(1, 2, 3), scales);
        assertTrue(drain().isEmpty(), "a scale the game offers is not reported");
    }

    @Test
    void aScaleThatIsZeroFractionalOrOutOfRangeIsReportedAndNotClamped() {
        long context = GrugTestContexts.tick();

        HostFunctions.TestTickContext_set_gui_scale(context, 0);
        HostFunctions.TestTickContext_set_gui_scale(context, 1.5);
        HostFunctions.TestTickContext_set_gui_scale(context, 4);
        HostFunctions.TestTickContext_set_gui_scale(context, Double.NaN);
        HostFunctions.TestTickContext_set_gui_scale(context, Double.POSITIVE_INFINITY);

        List<String> reports = drain();
        assertEquals(5, reports.size());
        assertTrue(reports.get(0).contains("from 1 to 3"), reports.get(0));
        assertTrue(scales.isEmpty(), "a scale the game does not offer never reaches the loader");
    }

    @Test
    void anUpperCaseLetterOrDigitIsHandedToTheLoader() {
        long context = GrugTestContexts.tick();

        HostFunctions.TestTickContext_press_game_key(context, 'B');
        HostFunctions.TestTickContext_press_game_key(context, '7');

        assertEquals(List.of(66, 55), keys);
        assertTrue(drain().isEmpty(), "a tracked key is not reported");
    }

    @Test
    void aKeyThatIsNotAnUpperCaseLetterOrDigitIsReportedAndNotPressed() {
        long context = GrugTestContexts.tick();

        HostFunctions.TestTickContext_press_game_key(context, 'a');
        HostFunctions.TestTickContext_press_game_key(context, 66.5);
        HostFunctions.TestTickContext_press_game_key(context, Double.NaN);
        HostFunctions.TestTickContext_press_game_key(context, 256);

        List<String> reports = drain();
        assertEquals(4, reports.size());
        assertTrue(reports.get(0).contains("upper case character code"), reports.get(0));
        assertTrue(keys.isEmpty(), "a key that names no letter or digit is never pressed");
    }
}
