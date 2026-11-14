package net.grug.minecraft.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.grug.minecraft.grug.GrugErrors;
import net.grug.minecraft.grug.GrugTestState;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * The operator host functions' path from a script to the player: the loader's own answer where the
 * player is held, and a report instead of a guess on a client joined to a server (#1331).
 */
class GrugOperatorCallsTest {
    /** An adapter that keeps one player's operator status and records what it was asked. */
    private static final class Operators {
        Object player = "the player";
        boolean operator;
        final List<String> calls = new ArrayList<>();

        ModLoaderAdapter adapter() {
            return (ModLoaderAdapter)
                    java.lang.reflect.Proxy.newProxyInstance(
                            ModLoaderAdapter.class.getClassLoader(),
                            new Class<?>[] {ModLoaderAdapter.class},
                            (proxy, method, args) -> {
                                switch (method.getName()) {
                                    case "testPlayer":
                                        return player;
                                    case "isOperator":
                                        calls.add("is " + args[0]);
                                        return operator;
                                    case "makeOperator":
                                        calls.add("make " + args[0]);
                                        operator = true;
                                        return null;
                                    case "removeOperator":
                                        calls.add("remove " + args[0]);
                                        operator = false;
                                        return null;
                                    default:
                                        throw new AssertionError(
                                                "an operator call does not ask "
                                                        + method.getName());
                                }
                            });
        }
    }

    private boolean savedRunActive;

    @BeforeEach
    void startWithNoRunActive() {
        savedRunActive = GrugTestState.testRunActive;
        GrugTestState.testRunActive = false;
        GrugErrors.takeHostFunctionException();
        leaveTheRun();
    }

    @AfterEach
    void restoreTheRunAndWhatWasReported() {
        GrugTestState.testRunActive = savedRunActive;
        GrugErrors.takeHostFunctionException();
        synchronized (GrugErrors.runtimeErrorQueue) {
            GrugErrors.runtimeErrorQueue.clear();
        }
        leaveTheRun();
    }

    /** Replaces the process's client with one that never started a run. */
    private static void leaveTheRun() {
        new GrugControlClient(
                message -> {
                    throw new AssertionError("nothing was expected to be sent");
                },
                (eye, x1, y1, x2, y2, done, failed) -> {
                    throw new AssertionError("no capture was expected");
                },
                null,
                -1,
                -1);
    }

    @Test
    void theTestPlayerIsReadGrantedAndRevokedThroughTheAdapter() {
        Operators operators = new Operators();
        ModLoaderAdapter adapter = operators.adapter();

        assertFalse(GrugOperatorCalls.isOperator(adapter, "is_operator"));
        GrugOperatorCalls.setOperator(adapter, true, "set_operator");
        assertTrue(GrugOperatorCalls.isOperator(adapter, "is_operator"));
        GrugOperatorCalls.setOperator(adapter, false, "set_operator");
        assertFalse(GrugOperatorCalls.isOperator(adapter, "is_operator"));

        assertEquals(
                List.of(
                        "is the player",
                        "make the player",
                        "is the player",
                        "remove the player",
                        "is the player"),
                operators.calls);
    }

    @Test
    void aCallWithNoPlayerYetIsReportedAndAnswersNothing() {
        Operators operators = new Operators();
        operators.player = null;
        ModLoaderAdapter adapter = operators.adapter();
        GrugTestState.testRunActive = true;

        assertFalse(GrugOperatorCalls.isOperator(adapter, "is_operator"));
        String isReport = GrugErrors.takeHostFunctionException();
        GrugOperatorCalls.setOperator(adapter, true, "set_operator");
        String setReport = GrugErrors.takeHostFunctionException();

        assertTrue(isReport.contains("is_operator: There is no player"), isReport);
        assertTrue(setReport.contains("set_operator: There is no player"), setReport);
        assertTrue(operators.calls.isEmpty(), "no loader was asked about nobody");
    }

    @Test
    void aClientJoinedToAServerReportsInsteadOfAnsweringFromItsOwnState() {
        Operators operators = new Operators();
        operators.operator = true;
        ModLoaderAdapter adapter = operators.adapter();
        GrugControlClient client =
                new GrugControlClient(
                        message -> {},
                        (eye, x1, y1, x2, y2, done, failed) -> {
                            throw new AssertionError("no capture was expected");
                        },
                        GrugControlClient.adapterCaller(operators::adapter),
                        -1,
                        -1);
        client.beginRun();
        GrugTestState.testRunActive = true;

        // The server holds the list, so a joined client's own answer would be a guess: the read
        // says false and changes nothing, and each call is the error of the test that made it.
        assertFalse(GrugOperatorCalls.isOperator(adapter, "is_operator"));
        String isReport = GrugErrors.takeHostFunctionException();
        GrugOperatorCalls.setOperator(adapter, false, "set_operator");
        String setReport = GrugErrors.takeHostFunctionException();

        assertTrue(isReport.contains("is_operator: A client joined to a server"), isReport);
        assertTrue(setReport.contains("set_operator: A client joined to a server"), setReport);
        assertTrue(operators.calls.isEmpty(), "the client's own adapter was not asked");
        assertTrue(operators.operator, "and nothing changed");
        assertNull(GrugErrors.takeHostFunctionException());
    }
}
