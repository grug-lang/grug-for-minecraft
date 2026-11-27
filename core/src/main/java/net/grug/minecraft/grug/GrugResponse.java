package net.grug.minecraft.grug;

/**
 * The shared base of every response class in {@code mod_api.json}: an object the host passes to an
 * export so the export can tell the host something, since a grug export cannot return a value.
 *
 * <p>Three rules hold for every response class, and each is an err when broken:
 *
 * <ol>
 *   <li>It is only valid during the call it was passed to.
 *   <li>It is answered at most once, and a second answer is an err even when it repeats the first.
 *   <li>It has to be answered: an export that returns without answering is an err raised by the
 *       host that called it.
 * </ol>
 *
 * <p>A response class builds on this by calling {@link #beginAnswer} first in each of its answering
 * methods and recording the answer only when that returns true, so the rules live here once and a
 * class's own tests prove it is wired to them.
 */
public abstract class GrugResponse {
    private final String exportName;
    private boolean open = true;
    private boolean answered = false;

    protected GrugResponse(String exportName) {
        this.exportName = exportName;
    }

    /**
     * Whether the call this response was passed to is still running, which a getter that neither
     * answers nor changes the answer checks before it reads anything.
     *
     * @param method the grug method being called, for the err message
     * @return whether the caller may read the response
     */
    protected final boolean checkOpen(String method) {
        if (open) return true;
        reportClosed(method);
        return false;
    }

    private void reportClosed(String method) {
        report(
                method
                        + ": the response is only valid during the "
                        + exportName
                        + " call it was passed to, and that call has returned.");
    }

    /**
     * Reports a broken rule of this response as an err of the call it was passed to.
     *
     * <p>The default hands the message to {@link Grug#hostFunctionErrorHappened}, which fails the
     * grug call the response belongs to while one is running and otherwise records it against the
     * running test. A response the host asks for on its own, with no call of the test's around it,
     * overrides this so its defect is contained the way the mod's own err is: see {@link
     * GrugListRowResponse}.
     *
     * @param message what the export did wrong, naming the grug method it called
     */
    protected void report(String message) {
        Grug.hostFunctionErrorHappened(Grug.statePtr, message);
    }

    /**
     * Claims the one answer this response allows.
     *
     * @param method the grug method being called, for the err message
     * @return whether the caller may record its answer; false means an err was reported
     */
    protected final boolean beginAnswer(String method) {
        if (!open) {
            reportClosed(method);
            return false;
        }
        if (answered) {
            report(
                    method
                            + ": "
                            + exportName
                            + " has already been answered, and a response is answered once.");
            return false;
        }
        answered = true;
        return true;
    }

    /** Ends the call without judging it, for an export that did not run at all. */
    public final void close() {
        open = false;
    }

    /**
     * Ends the call this response was passed to: from here on it is stale, and an export that
     * returned without answering is an err.
     *
     * @return whether the export answered
     */
    public final boolean finish() {
        open = false;
        if (!answered) {
            report(
                    exportName
                            + " returned without answering its response, and every response has"
                            + " to be answered before the call returns.");
        }
        return answered;
    }
}
