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
 *   <li>It has to be answered: an export that returns without answering is an err raised by the host
 *       that called it.
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
     * Claims the one answer this response allows.
     *
     * @param method the grug method being called, for the err message
     * @return whether the caller may record its answer; false means an err was reported
     */
    protected final boolean beginAnswer(String method) {
        if (!open) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr,
                    method
                            + ": the response is only valid during the "
                            + exportName
                            + " call it was passed to, and that call has returned.");
            return false;
        }
        if (answered) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr,
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
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr,
                    exportName
                            + " returned without answering its response, and refusing has to be"
                            + " said with response.refuse().");
        }
        return answered;
    }
}
