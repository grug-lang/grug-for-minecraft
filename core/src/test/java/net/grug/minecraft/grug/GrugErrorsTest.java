package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Covers the message a Java exception a host function threw is reported with.
 *
 * <p>The generated JNI wrapper cannot let the exception unwind through grug-rs's Rust frames, so it
 * hands the exception to {@link GrugErrors#onHostFunctionThrew} and reports the answer as a host
 * function error. That message is what names the host function and keeps the Java stack, so it is
 * measured here, without a live grug state.
 */
class GrugErrorsTest {

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
    void namesTheHostFunctionThatThrewAndKeepsItsStack() {
        String message = GrugErrors.onHostFunctionThrew(thrownFrom(hostFunctionFrame()));

        // The host function, named by the class and method the bridge called. That frame is the
        // last of the stack, because a call from native code starts the Java stack there.
        assertTrue(message.contains("net.grug.minecraft.grug.HostFunctions"), message);
        assertTrue(message.contains("Screenshot_differs_from"), message);
        assertTrue(message.contains("java.lang.NullPointerException"), message);
        assertTrue(message.contains("the id resolved to no object in the entity map"), message);
        // The JNI layer used to print the stack itself, so the report has to carry it instead, and
        // it says which line of the host function is at fault.
        assertTrue(message.contains("HostFunctions.java:1151"), message);
        // A reader has to know the defect is not in the test's own script.
        assertTrue(message.contains("defect in grug"), message);
    }

    @Test
    void saysAHostFunctionThrewWhenTheExceptionHasNoJavaStack() {
        // A throwable can carry no frames at all, and a report that named a host function then
        // would name one that is not there.
        String message = GrugErrors.onHostFunctionThrew(thrownFrom());

        assertTrue(message.startsWith("A host function threw a Java exception"), message);
        assertTrue(message.contains("java.lang.NullPointerException"), message);
        assertTrue(message.contains("defect in grug"), message);
    }
}
