package net.grug.minecraft.grug;

/**
 * Hands a unit test the context id a host function on {@code TestTickContext} or {@code
 * TestInitContext} takes first, for a call that a running test would have made through {@code ctx}.
 * A test that uses it closes the context again with {@link #close()}.
 */
public final class GrugTestContexts {
    private GrugTestContexts() {}

    /** The id of an open tick context. */
    public static long tick() {
        GrugTestState.closeTickContext();
        return GrugEntities.addEntity(
                GrugEntityType.TestTickContext, GrugTestState.openTickContext());
    }

    /** The id of an open init context. */
    public static long init() {
        GrugTestState.closeInitContext();
        return GrugEntities.addEntity(
                GrugEntityType.TestInitContext, GrugTestState.openInitContext());
    }

    /** Closes whichever contexts are open. */
    public static void close() {
        GrugTestState.closeTickContext();
        GrugTestState.closeInitContext();
    }
}
