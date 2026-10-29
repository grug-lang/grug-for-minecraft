package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.grug.minecraft.core.GrugTestRunner;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Covers {@code TestInitContext.set_max_ticks}, which a test's {@code init()} calls to raise its
 * budget. The refusals are mod-tree defects, so a Java test drives them without a live grug state.
 */
class HostFunctionsTestInitContextTest {

    @BeforeEach
    @AfterEach
    void clearState() {
        GrugTestState.closeInitContext();
        GrugTestState.maxTestTicks = GrugTestRunner.DEFAULT_MAX_TEST_TICKS;
        GrugTestState.gameSpeed = 1;
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
                GrugEntityType.TestInitContext, GrugTestState.openInitContext());
    }

    @Test
    void raisesTheBudget() {
        long context = contextId();

        HostFunctions.TestInitContext_set_max_ticks(context, 1000);

        assertEquals(1000, GrugTestState.maxTestTicks);
        assertTrue(drain().isEmpty(), "A legal call reports nothing.");
    }

    @Test
    void acceptsTheDefaultAndTheLargestInt() {
        long context = contextId();

        HostFunctions.TestInitContext_set_max_ticks(context, GrugTestRunner.DEFAULT_MAX_TEST_TICKS);
        assertEquals(GrugTestRunner.DEFAULT_MAX_TEST_TICKS, GrugTestState.maxTestTicks);

        HostFunctions.TestInitContext_set_max_ticks(context, Integer.MAX_VALUE);
        assertEquals(Integer.MAX_VALUE, GrugTestState.maxTestTicks);
        assertTrue(drain().isEmpty());
    }

    @Test
    void refusesWhatIsNotAWholeNumberFromTheDefaultUpToAnInt() {
        long context = contextId();
        double[] refused = {
            GrugTestRunner.DEFAULT_MAX_TEST_TICKS - 1,
            0,
            -5,
            250.5,
            (double) Integer.MAX_VALUE + 1,
            Double.NaN,
            Double.POSITIVE_INFINITY
        };

        for (double ticks : refused) {
            HostFunctions.TestInitContext_set_max_ticks(context, ticks);
        }

        List<String> reports = drain();
        assertEquals(refused.length, reports.size(), "Every refusal is reported.");
        for (String report : reports) {
            assertTrue(report.contains("TestInitContext.set_max_ticks"), report);
            assertTrue(report.contains("whole number from 200 to 2147483647"), report);
        }
        assertEquals(
                GrugTestRunner.DEFAULT_MAX_TEST_TICKS,
                GrugTestState.maxTestTicks,
                "A refused value changes nothing.");
    }

    @Test
    void aContextSavedAfterItsCallIsRefused() {
        long context = contextId();
        GrugTestState.closeInitContext();

        HostFunctions.TestInitContext_set_max_ticks(context, 1000);

        List<String> reports = drain();
        assertEquals(1, reports.size());
        assertTrue(reports.get(0).contains("TestInitContext.set_max_ticks"), reports.get(0));
        assertTrue(reports.get(0).contains("saved in a member variable"), reports.get(0));
        assertEquals(GrugTestRunner.DEFAULT_MAX_TEST_TICKS, GrugTestState.maxTestTicks);
    }

    @Test
    void theTickContextIsNotTheInitContext() {
        long tick =
                GrugEntities.addEntity(
                        GrugEntityType.TestTickContext, GrugTestState.openTickContext());
        GrugTestState.openInitContext();

        HostFunctions.TestInitContext_set_max_ticks(tick, 1000);

        assertEquals(1, drain().size());
        GrugTestState.closeTickContext();
    }

    @Test
    void asksForAGameSpeed() {
        long context = contextId();

        HostFunctions.TestInitContext_set_game_speed(context, 10);
        assertEquals(10, GrugTestState.gameSpeed);

        HostFunctions.TestInitContext_set_game_speed(context, 1);
        assertEquals(1, GrugTestState.gameSpeed);

        HostFunctions.TestInitContext_set_game_speed(context, GrugTestRunner.MAX_GAME_SPEED);
        assertEquals(GrugTestRunner.MAX_GAME_SPEED, GrugTestState.gameSpeed);
        assertTrue(drain().isEmpty(), "A legal call reports nothing.");
    }

    @Test
    void refusesAGameSpeedThatIsNotAWholeNumberFromOneToTheMaximum() {
        long context = contextId();
        double[] refused = {
            0,
            -1,
            0.5,
            2.5,
            GrugTestRunner.MAX_GAME_SPEED + 1,
            Double.NaN,
            Double.POSITIVE_INFINITY,
            Double.NEGATIVE_INFINITY
        };

        for (double speed : refused) {
            HostFunctions.TestInitContext_set_game_speed(context, speed);
        }

        List<String> reports = drain();
        assertEquals(refused.length, reports.size(), "Every refusal is reported.");
        for (String report : reports) {
            assertTrue(report.contains("TestInitContext.set_game_speed"), report);
            assertTrue(report.contains("whole number from 1 to 100"), report);
        }
        assertEquals(1, GrugTestState.gameSpeed, "A refused value changes nothing.");
    }

    @Test
    void aContextSavedAfterItsCallCannotSetAGameSpeed() {
        long context = contextId();
        GrugTestState.closeInitContext();

        HostFunctions.TestInitContext_set_game_speed(context, 10);

        List<String> reports = drain();
        assertEquals(1, reports.size());
        assertTrue(reports.get(0).contains("TestInitContext.set_game_speed"), reports.get(0));
        assertEquals(1, GrugTestState.gameSpeed);
    }
}
