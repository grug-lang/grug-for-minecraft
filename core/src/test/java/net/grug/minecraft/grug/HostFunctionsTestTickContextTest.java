package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * Covers the methods a test's {@code tick()} reaches through the context it is handed.
 *
 * <p>Like the render contexts, a run context is only valid while the call it was passed to is
 * running, and a context saved in a member variable and used in a later tick is refused with a
 * report. That refusal needs no live grug state, so it is measured here for every method.
 */
class HostFunctionsTestTickContextTest {

    @BeforeEach
    @AfterEach
    void clearState() {
        GrugTestState.closeTickContext();
        GrugTestState.testNotDone = false;
        GrugTestState.testExpectedError = null;
        GrugTestState.currentTestTick = 0;
        GrugEntities.entityData.clear();
        drain();
    }

    private static List<String> drain() {
        synchronized (GrugErrors.runtimeErrorQueue) {
            List<String> reports = new ArrayList<>(GrugErrors.runtimeErrorQueue);
            GrugErrors.runtimeErrorQueue.clear();
            return reports;
        }
    }

    private static long contextId() {
        return GrugEntities.addEntity(
                GrugEntityType.TestTickContext, GrugTestState.openTickContext());
    }

    @Test
    void theOpenContextAnswersTheTestsTickAndRecordsItsRequests() {
        GrugTestState.currentTestTick = 7;
        long context = contextId();

        HostFunctions.TestTickContext_assert(context, true, "holds");
        HostFunctions.TestTickContext_not_done(context);
        HostFunctions.TestTickContext_expect_error(context, "boom");

        assertEquals(7.0, HostFunctions.TestTickContext_current_tick(context));
        assertTrue(GrugTestState.testNotDone);
        assertEquals("boom", GrugTestState.testExpectedError);
        assertTrue(drain().isEmpty(), "A legal call reports nothing.");
    }

    @Test
    void aContextSavedAfterItsCallIsRefusedByEveryMethod() {
        long context = contextId();
        GrugTestState.closeTickContext();

        HostFunctions.TestTickContext_assert(context, true, "holds");
        HostFunctions.TestTickContext_current_tick(context);
        HostFunctions.TestTickContext_not_done(context);
        HostFunctions.TestTickContext_expect_error(context, "boom");

        List<String> reports = drain();
        assertEquals(4, reports.size(), "Every method names its own refusal.");
        assertTrue(reports.get(0).contains("TestTickContext.assert"), reports.get(0));
        assertTrue(reports.get(1).contains("TestTickContext.current_tick"), reports.get(1));
        assertTrue(reports.get(2).contains("TestTickContext.not_done"), reports.get(2));
        assertTrue(reports.get(3).contains("TestTickContext.expect_error"), reports.get(3));
        for (String report : reports) {
            assertTrue(report.contains("saved in a member variable"), report);
        }
        assertFalse(GrugTestState.testNotDone, "A refused call changes nothing.");
        assertEquals(null, GrugTestState.testExpectedError);
    }

    @Test
    void aContextFromAnEarlierCallIsRefusedWhileALaterOneIsOpen() {
        long stale = contextId();
        long current = contextId();

        HostFunctions.TestTickContext_not_done(stale);

        assertEquals(1, drain().size());
        assertFalse(GrugTestState.testNotDone);
        HostFunctions.TestTickContext_not_done(current);
        assertTrue(GrugTestState.testNotDone);
    }

    @Test
    void aContextSavedAfterItsCallIsRefusedByEveryHostFunctionOfTheContext() throws Exception {
        long context = contextId();
        GrugTestState.closeTickContext();
        int methods = 0;

        for (Method method : HostFunctions.class.getDeclaredMethods()) {
            if (!method.getName().startsWith("TestTickContext_")
                    || !Modifier.isPublic(method.getModifiers())) {
                continue;
            }
            Object[] arguments = new Object[method.getParameterCount()];
            Class<?>[] types = method.getParameterTypes();
            for (int i = 0; i < types.length; i++) {
                arguments[i] = i == 0 ? (Object) context : defaultOf(types[i]);
            }
            method.invoke(null, arguments);
            methods++;
        }

        List<String> reports = drain();
        assertEquals(methods, reports.size(), "Every method names its own refusal.");
        for (String report : reports) {
            assertTrue(report.contains("saved in a member variable"), report);
        }
        assertTrue(methods > 40, "The reflection found the host functions, not an empty set.");
    }

    @Test
    void everyRefusalNamesTheMethodThatMadeIt() throws Exception {
        long context = contextId();
        GrugTestState.closeTickContext();

        HostFunctions.TestTickContext_screenshot(context);
        HostFunctions.TestTickContext_get_origin(context);

        List<String> reports = drain();
        assertEquals(2, reports.size());
        assertTrue(reports.get(0).contains("TestTickContext.screenshot"), reports.get(0));
        assertTrue(reports.get(1).contains("TestTickContext.get_origin"), reports.get(1));
    }

    private static Object defaultOf(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == double.class) {
            return 0.0;
        }
        if (type == long.class) {
            return 0L;
        }
        return "";
    }
}
