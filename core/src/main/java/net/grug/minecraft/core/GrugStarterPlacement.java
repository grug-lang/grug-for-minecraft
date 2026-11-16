package net.grug.minecraft.core;

/**
 * Whether a dedicated server still has to put the run's starter at the save's start (#1347).
 *
 * <p>The server places the player who started the run once, before the runner exists. A flag that
 * said "someone was placed" held for the whole process, so a second starter, who owns the run once
 * the first has left before the runner began, was never placed and stood wherever their client
 * reported. The decision remembers the starter it placed instead, so each new starter is placed
 * once. It lives here, where a unit test reaches every case, and the loaders' {@code GrugServerRun}
 * only teleports.
 */
public final class GrugStarterPlacement {
    private Object placed;

    /**
     * True once for each distinct starter: when {@code starter} is not the one placed last. A null
     * starter, which means no player has started a run, is never placed and keeps the one before.
     */
    public boolean shouldPlace(Object starter) {
        if (starter == null || starter == placed) {
            return false;
        }
        placed = starter;
        return true;
    }
}
