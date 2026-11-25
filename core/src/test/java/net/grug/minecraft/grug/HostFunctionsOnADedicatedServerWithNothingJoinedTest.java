package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.grug.minecraft.core.GrugClientTestCalls;
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
 * Covers the arms that answer a call a dedicated server cannot serve: {@code
 * ModLoaderAdapter.refuseWithoutAClient} refusing a call that needs a client, {@code
 * TestTickContext.set_mouse_position} refusing to park the cursor a capture would show, and {@code
 * TestTickContext.get_client_level}, {@code TestTickContext.get_player} and {@code
 * TestTickContext.get_origin} reporting that the server has no level, no player and no test origin
 * yet. Those arms used to carry a {@code @GrugGenerated} exclusion, because only a dedicated server
 * reaches them and no CI run launched one until #163. See #169 and #188.
 *
 * <p>It also covers the block selection a dedicated server has no box for: with no client joined to
 * forward it to, the call is the same refusal with the same message as before the forwarding
 * existed (#1441).
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

        assertTrue(GrugCore.getAdapter().refuseWithoutAClient("TestTickContext.click_screen"));

        assertEquals(
                List.of(
                        "TestTickContext.click_screen: This needs a client, and grug is running on"
                                + " a dedicated server."),
                reported);
    }

    @Test
    void aClientRunsTheSameCallAndReportsNothing() throws Exception {
        installAnAdapter(GrugSide.CLIENT);

        assertFalse(GrugCore.getAdapter().refuseWithoutAClient("TestTickContext.click_screen"));

        assertEquals(List.of(), reported);
    }

    @Test
    void aDedicatedServerWithNoClientToForwardToRefusesTheBlockSelectionAsItAlwaysDid()
            throws Exception {
        installAnEmptyDedicatedServer();

        assertFalse(
                GrugClientTestCalls.blockSelectionContains(
                        GrugCore.getAdapter(), new Object(), 1, 2, 3, 0.25, 0.5, 0.75));

        assertEquals(
                List.of(
                        "TestTickContext.block_selection: This needs a client, and grug is running"
                                + " on a dedicated server."),
                reported);
    }

    @Test
    void aDedicatedServerRefusesToParkTheCursorForACapture() throws Exception {
        installAnEmptyDedicatedServer();

        HostFunctions.TestTickContext_set_mouse_position(GrugTestContexts.tick(), 0.0, 0.0);

        assertEquals(
                List.of(
                        "TestTickContext.set_mouse_position: This needs a client, and grug is"
                                + " running on a dedicated server."),
                reported);
    }

    @Test
    void thereIsNoLevelToTestInYetAndTheReportSaysWhichSideAndFunction() throws Exception {
        installAnEmptyDedicatedServer();

        assertEquals(0L, HostFunctions.TestTickContext_get_client_level(GrugTestContexts.tick()));

        assertEquals(
                List.of(
                        "TestTickContext.get_client_level: There is no level to test in on a"
                                + " dedicated server yet."),
                reported);
    }

    @Test
    void thereIsNoPlayerToTestFromYetAndTheReportSaysWhichSideAndFunction() throws Exception {
        installAnEmptyDedicatedServer();

        assertEquals(0L, HostFunctions.TestTickContext_get_player(GrugTestContexts.tick()));

        assertEquals(
                List.of(
                        "TestTickContext.get_player: There is no player to test from on a dedicated"
                                + " server yet."),
                reported);
    }

    @Test
    void thereIsNoOriginToMeasureYetAndTheReportSaysWhichSideAndFunction() throws Exception {
        installAnEmptyDedicatedServer();

        assertEquals(0L, HostFunctions.TestTickContext_get_origin(GrugTestContexts.tick()));

        assertEquals(
                List.of(
                        "TestTickContext.get_origin: There is no test player to measure an origin"
                                + " from on a dedicated server yet."),
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
                        case "blockSelectionContains":
                            // 1.2.5's server answers a block selection by refusing it, because
                            // its block class has no selection box, and then answers false; with
                            // no client to forward to that refusal is what a test still gets
                            // (#1441).
                            InvocationHandler.invokeDefault(
                                    proxy,
                                    ModLoaderAdapter.class.getMethod(
                                            "refuseWithoutAClient", String.class),
                                    "TestTickContext.block_selection");
                            return false;
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
