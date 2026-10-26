package net.grug.minecraft.grug;

/**
 * Runs a block's {@code use} export for a loader and says whether the block consumed the click.
 *
 * <p>A {@code use} export returns nothing, so a block that has the export has handled the click
 * unless it calls {@code ctx.decline_use()}, which leaves the click to the game as if the block had
 * no {@code use} export, so the held item is used or placed as usual.
 */
public final class GrugBlockUse {
    /**
     * The context the open {@code use()} call was passed, which is what the context's method checks
     * before it acts. A new one is made for every call and cleared when it returns, so a context
     * saved in a member variable and used in a later click is refused.
     */
    public static final class Context {
        @GrugGenerated("marker: only a use call makes one")
        private Context() {}
    }

    private static boolean declined = false;
    private static Context context = null;

    private GrugBlockUse() {}

    /** The context of the {@code use()} call that is open right now, or null between calls. */
    public static Context context() {
        return context;
    }

    /** Opens the context of one {@code use()} call. */
    static Context openContext() {
        context = new Context();
        return context;
    }

    /** Closes it again, so a saved context no longer matches. */
    static void closeContext() {
        context = null;
    }

    /** Called by the {@code UseContext.decline_use} host function. */
    static void decline() {
        declined = true;
    }

    /** Whether the block consumed the click: it has a {@code use} export and did not decline. */
    public static boolean use(
            long blockHandle, long worldId, double x, double y, double z, long playerId) {
        declined = false;
        long contextHandle = GrugEntities.addEntity(GrugEntityType.UseContext, openContext());
        try {
            boolean handled =
                    ExportFns.Block_use(blockHandle, contextHandle, worldId, x, y, z, playerId);
            return handled && !declined;
        } finally {
            GrugEntities.removeEntity(contextHandle);
            closeContext();
        }
    }
}
