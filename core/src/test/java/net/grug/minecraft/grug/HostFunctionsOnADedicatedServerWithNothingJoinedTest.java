package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.grug.minecraft.core.GrugCore;
import net.grug.minecraft.core.GrugSide;
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
 * Covers the four arms that answer a call a dedicated server cannot serve: {@code
 * ModLoaderAdapter.refuseWithoutAClient} refusing a call that needs a client, and {@code
 * Test.get_client_level}, {@code Test.get_player} and {@code Test.get_origin} reporting that the
 * server has no level, no player and no test origin yet. Those arms used to carry a
 * {@code @GrugGenerated} exclusion, because only a dedicated server reaches them and no CI run
 * launched one until #163. See #169 and #188.
 *
 * <p>No game and no loader is needed: the side, the empty world and the report all reach a stand-in
 * adapter installed on {@code GrugCore.adapter}, the same reflection seam the other core tests use,
 * so these arms are counted in the core test job's own exec data. The three non-null paths stay out
 * of this class: every loader's game run takes them, and core's report unions those legs with this
 * job's.
 */
class HostFunctionsOnADedicatedServerWithNothingJoinedTest {

    /** What the installed adapter's {@code logError} was told, which is where a report arrives. */
    private final List<String> reported = new ArrayList<>();

    private Object savedAdapter;
    private boolean savedTestRunActive;
    private Vec3 savedTestOrigin;

    @BeforeEach
    void saveTheRuntimeState() throws Exception {
        savedAdapter = adapterField().get(null);
        savedTestRunActive = GrugTestState.testRunActive;
        savedTestOrigin = GrugTestState.testOrigin;
        // With no run to fail, a report goes to the log and chat, which is the arm these tests
        // assert. A run another test left active would record it for the runner instead.
        GrugTestState.testRunActive = false;
        GrugTestState.testOrigin = null;
        forgetQueuedReports();
    }

    @AfterEach
    void restoreTheRuntimeState() throws Exception {
        adapterField().set(null, savedAdapter);
        GrugTestState.testRunActive = savedTestRunActive;
        GrugTestState.testOrigin = savedTestOrigin;
        forgetQueuedReports();
    }

    @Test
    void aDedicatedServerRefusesAClientOnlyCallAndNamesTheFunctionAndTheSide() throws Exception {
        installAnEmptyDedicatedServer();

        assertTrue(GrugCore.getAdapter().refuseWithoutAClient("Test.click_screen"));

        assertEquals(
                List.of(
                        "Test.click_screen: This needs a client, and grug is running on a dedicated"
                                + " server."),
                reported);
    }

    @Test
    void aClientRunsTheSameCallAndReportsNothing() throws Exception {
        installAnAdapter(GrugSide.CLIENT);

        assertFalse(GrugCore.getAdapter().refuseWithoutAClient("Test.click_screen"));

        assertEquals(List.of(), reported);
    }

    @Test
    void thereIsNoLevelToTestInYetAndTheReportSaysWhichSideAndFunction() throws Exception {
        installAnEmptyDedicatedServer();

        assertEquals(0L, HostFunctions.Test_get_client_level());

        assertEquals(
                List.of(
                        "Test.get_client_level: There is no level to test in on a dedicated server"
                                + " yet."),
                reported);
    }

    @Test
    void thereIsNoPlayerToTestFromYetAndTheReportSaysWhichSideAndFunction() throws Exception {
        installAnEmptyDedicatedServer();

        assertEquals(0L, HostFunctions.Test_get_player());

        assertEquals(
                List.of(
                        "Test.get_player: There is no player to test from on a dedicated server"
                                + " yet."),
                reported);
    }

    @Test
    void thereIsNoOriginToMeasureYetAndTheReportSaysWhichSideAndFunction() throws Exception {
        installAnEmptyDedicatedServer();

        assertEquals(0L, HostFunctions.Test_get_origin());

        assertEquals(
                List.of(
                        "Test.get_origin: There is no test player to measure an origin from on a"
                                + " dedicated server yet."),
                reported);
    }

    private void installAnEmptyDedicatedServer() throws Exception {
        installAnAdapter(GrugSide.DEDICATED_SERVER);
    }

    /**
     * Installs a stand-in adapter that says which side it is on, has no level, no test player and
     * no origin, and keeps every report it is given. Any other call fails the test by name, so an
     * arm that reached past those answers is reported rather than silently answered null.
     */
    private void installAnAdapter(GrugSide side) throws Exception {
        InvocationHandler handler =
                (proxy, method, args) -> {
                    // The refusal is the interface's default method, so calling it has to run the
                    // interface's own code rather than a stub of it.
                    if (method.isDefault()) {
                        return InvocationHandler.invokeDefault(proxy, method, args);
                    }
                    switch (method.getName()) {
                        case "getSide":
                            return side;
                        case "logError":
                            reported.add((String) args[0]);
                            return null;
                        case "getLevel":
                        case "testPlayer":
                        case "getTestOrigin":
                            return null;
                        default:
                            throw new AssertionError(
                                    "the arm reached " + method.getName() + " on the adapter");
                    }
                };
        adapterField()
                .set(
                        null,
                        Proxy.newProxyInstance(
                                getClass().getClassLoader(),
                                new Class<?>[] {ModLoaderAdapter.class},
                                handler));
    }

    /** Empties the chat channel of reports another test may have left on it. */
    private static void forgetQueuedReports() {
        synchronized (GrugErrors.runtimeErrorQueue) {
            GrugErrors.runtimeErrorQueue.clear();
        }
    }

    private static Field adapterField() throws NoSuchFieldException {
        Field field = GrugCore.class.getDeclaredField("adapter");
        field.setAccessible(true);
        return field;
    }
}
