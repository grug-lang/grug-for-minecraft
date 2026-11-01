package net.grug.minecraft.forge;

/**
 * What a {@code ThreadedLevelLightEngine} still has to do, which the settle answer asks for and the
 * engine does not answer publicly. The mixin next to this interface is the implementation. See
 * #686.
 */
public interface GrugLightEngineState {
    /** Whether the engine holds checks or batch runs it has not run yet. */
    boolean grug$hasQueuedLightTasks();

    /** Whether a batch is already scheduled on the engine's light thread. */
    boolean grug$isUpdateScheduled();
}
