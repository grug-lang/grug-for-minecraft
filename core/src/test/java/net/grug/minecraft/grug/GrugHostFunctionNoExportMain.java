package net.grug.minecraft.grug;

import java.io.File;

/**
 * Boots a grug state, reports a host function error with no export active and prints what was
 * recorded, in a JVM of its own.
 *
 * <p>A build without the guard in {@link Grug#hostFunctionErrorHappened} reaches grug-rs's native
 * report with an empty call stack, whose panic cannot unwind through the JNI boundary and aborts
 * the process (#1134). Here that abort is this process's exit status rather than the death of the
 * test JVM that started it, which is the assertion {@link
 * GrugHostFunctionExceptionTest#aHostFunctionErrorWithNoExportToFailDoesNotAbortTheProcess} makes.
 */
public final class GrugHostFunctionNoExportMain {

    /** The text the record is printed under, so the test can tell a record from a bare exit. */
    static final String RECORDED_PREFIX = "RECORDED: ";

    private GrugHostFunctionNoExportMain() {}

    public static void main(String[] args) {
        Grug.init(new File(args[0]), new File(args[1]));
        if (Grug.statePtr == 0) {
            System.out.println("NO STATE");
            System.exit(2);
        }
        Grug.hostFunctionErrorHappened(Grug.statePtr, args[2]);
        System.out.println(RECORDED_PREFIX + GrugErrors.takeHostFunctionException());
        System.out.flush();
        // grug's poll thread is not one this process can join, so the exit status is stated here;
        // the aborted build never reaches this line.
        System.exit(0);
    }
}
