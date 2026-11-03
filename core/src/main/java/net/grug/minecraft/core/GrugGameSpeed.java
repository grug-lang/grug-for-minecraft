package net.grug.minecraft.core;

/** Turns a requested game speed into whole steps for a loader that can only take whole ones. */
public final class GrugGameSpeed {
    private GrugGameSpeed() {}

    /**
     * The ticks to run for each tick of the runner: the speed cut to a whole number, and never
     * below one, because a speed under 1 would cut to 0 and the runner would then never tick.
     */
    public static int wholeSteps(double speed) {
        return Math.max(1, (int) speed);
    }
}
