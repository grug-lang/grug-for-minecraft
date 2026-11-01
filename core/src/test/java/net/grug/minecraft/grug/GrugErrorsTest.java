package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;

/**
 * Covers the message a Java exception a host function threw is reported with.
 *
 * <p>The generated JNI wrapper cannot let the exception unwind through grug-rs's Rust frames, so it
 * hands the exception to {@link GrugErrors#onHostFunctionThrew} and reports the answer as a host
 * function error. That message is what names the host function and keeps the Java stack, so it is
 * measured here, without a live grug state; the wrapper that calls it is driven by {@code
 * GrugHostFunctionExceptionTest}.
 */
class GrugErrorsTest {

    @AfterEach
    void forgetAnyRecord() {
        GrugErrors.takeHostFunctionException();
    }

    /**
     * A helper the host function calls, which is where a nested throw happens. It is the first
     * frame, so a report that named the first frame would name this instead of the function.
     */
    private static StackTraceElement helperFrame() {
        return new StackTraceElement(
                "net.grug.minecraft.grug.GrugScreenshots",
                "changedPercent",
                "GrugScreenshots.java",
                120);
    }

    /** The frame the native bridge calls a host function from, which is the last of its stack. */
    private static StackTraceElement hostFunctionFrame() {
        return new StackTraceElement(
                "net.grug.minecraft.grug.HostFunctions",
                "Screenshot_differs_from",
                "HostFunctions.java",
                1151);
    }

    private static NullPointerException thrownFrom(StackTraceElement... stack) {
        NullPointerException thrown =
                new NullPointerException("the id resolved to no object in the entity map");
        thrown.setStackTrace(stack);
        return thrown;
    }

    @Test
    void namesTheHostFunctionTheBridgeCalledRatherThanItsHelper() {
        String message =
                GrugErrors.hostFunctionThrewMessage(
                        thrownFrom(helperFrame(), hostFunctionFrame()),
                        "Screenshot_differs_from",
                        false);

        // A host function that calls a helper which throws has the helper at the top of its stack
        // and the function below it. The function is the one the bridge called, so the report names
        // the last frame; naming the first would blame the helper, which the script never reached.
        assertTrue(
                message.startsWith(
                        "net.grug.minecraft.grug.HostFunctions.Screenshot_differs_from threw a Java"
                                + " exception"),
                message);
        assertFalse(
                message.startsWith("net.grug.minecraft.grug.GrugScreenshots.changedPercent threw"),
                message);
        assertTrue(message.contains("java.lang.NullPointerException"), message);
        assertTrue(message.contains("the id resolved to no object in the entity map"), message);
        // The JNI layer used to print the stack itself, so the report has to carry it instead, and
        // it says which line of the host function and of its helper is at fault.
        assertTrue(message.contains("HostFunctions.java:1151"), message);
        assertTrue(message.contains("GrugScreenshots.java:120"), message);
        // A reader has to know the defect is not in the test's own script.
        assertTrue(message.contains("defect in grug"), message);
    }

    @Test
    void namesTheFunctionWhenTheJvmDroppedTheStack() {
        // HotSpot hands back a shared, stackless instance for an implicit exception it throws
        // often, which is exactly a host function reading a stale id every tick, so a report that
        // read only the stack would say nothing about where the defect is.
        String message =
                GrugErrors.hostFunctionThrewMessage(thrownFrom(), "Screenshot_differs_from", false);

        assertTrue(
                message.startsWith(
                        "The host function Screenshot_differs_from threw a Java exception"),
                message);
        assertTrue(message.contains("java.lang.NullPointerException"), message);
        assertTrue(message.contains("dropped its stack"), message);
        assertTrue(message.contains("no frames and no line to name"), message);
        assertTrue(message.contains("defect in grug"), message);
    }

    @Test
    void blamesTheAdapterWhenAnArgumentCouldNotBePassed() {
        // The Java function never ran, so its frames say nothing about the defect: the adapter
        // could not pass the argument, and the message has to say so rather than blaming grug.
        String message =
                GrugErrors.hostFunctionThrewMessage(thrownFrom(hostFunctionFrame()), "print", true);

        assertTrue(
                message.startsWith(
                        "The adapter failed to pass an argument to the host function" + " print"),
                message);
        assertTrue(message.contains("defect in the adapter"), message);
        assertFalse(message.contains("defect in grug"), message);
        assertTrue(message.contains("HostFunctions.java:1151"), message);
    }

    @Test
    void anArgumentFailureSaysNothingAboutADroppedStack() {
        // The stackless sentence explains a host function's own throw. An adapter failure with no
        // frames is still the adapter's, and offering the function's missing stack as the
        // explanation would name the wrong side.
        String message = GrugErrors.hostFunctionThrewMessage(thrownFrom(), "print", true);

        assertTrue(
                message.startsWith(
                        "The adapter failed to pass an argument to the host function" + " print"),
                message);
        assertFalse(message.contains("dropped its stack"), message);
    }

    @Test
    void stillNamesTheFunctionWhenTheReportItselfFails() {
        // A throwable can override printStackTrace, and the wrapper has no second channel for a
        // report that failed, so the answer has to stay a message that names the function.
        String message = GrugErrors.hostFunctionThrewMessage(new Unprintable(), "print", false);

        assertTrue(message.startsWith("The host function print threw a Java exception"), message);
        assertTrue(message.contains("formatting the report failed too"), message);
        assertTrue(message.contains("IllegalStateException"), message);
        assertTrue(message.contains("defect in grug"), message);
    }

    @Test
    void keepsTheFirstHostFunctionExceptionUntilTheRunnerTakesIt() {
        // A defect that repeats every frame must not fill the record, and the first one is the one
        // that says where it started.
        GrugErrors.recordHostFunctionException("first");
        GrugErrors.recordHostFunctionException("second");

        assertEquals("first", GrugErrors.takeHostFunctionException());
        assertNull(GrugErrors.takeHostFunctionException());
    }

    /** A throwable whose stack cannot be printed, which the report has to survive. */
    private static final class Unprintable extends NullPointerException {
        @Override
        public void printStackTrace(PrintWriter writer) {
            throw new IllegalStateException("this stack cannot be printed");
        }
    }
}
