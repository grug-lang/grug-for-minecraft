package net.grug.minecraft.grug;

/**
 * The mod whose script a call into the VM is running, so an entry that has no mod-relative literal
 * of its own, such as {@code String.translate}, can reach the calling mod's files.
 *
 * <p>Most host entries learn the mod from the resource literal the compiler rewrote, as {@code
 * File.open(r"files")} does. A translated key names no such literal, so the mod is the one whose
 * overlay, screen or test export the call is in. Every dispatch that carries a mod sets it here for
 * the duration of the call and restores what was there before in a {@code finally}, so a call made
 * outside any script dispatch finds none and is refused rather than reading another mod's files.
 *
 * <p>The value is process-global rather than per thread because grug's calls are serialized by
 * {@link GrugStateLock}; a script call on the server thread and one on the render thread never run
 * together, so the dispatch that is running is the one that set the value. Restoring the previous
 * value rather than clearing it keeps a call that runs inside another, such as a screen's export
 * asked from an overlay's, answering for the inner script and then for the outer one again.
 *
 * <p>Do not confuse this with {@link GrugTestState#currentTestMod}: that is a mark the render
 * thread reads to tell whether the running test's own overlay drew, and it outlives a test call by
 * design.
 */
public final class GrugScriptDispatch {

    /** The mod whose script is running now, or null outside a dispatch of a mod's script. */
    private static volatile String runningMod = null;

    @GrugGenerated("utility class: never instantiated")
    private GrugScriptDispatch() {}

    /** The mod whose script is running now, or null outside a dispatch of a mod's script. */
    public static String runningMod() {
        return runningMod;
    }

    /**
     * Says that {@code mod}'s script is running now and answers the mod that was running before,
     * which the caller hands back to {@link #end} when its call returns.
     */
    public static String begin(String mod) {
        String previous = runningMod;
        runningMod = mod;
        return previous;
    }

    /** Puts back the mod {@link #begin} answered, after the dispatch it opened has returned. */
    public static void end(String previous) {
        runningMod = previous;
    }
}
