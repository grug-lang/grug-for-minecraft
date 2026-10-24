package net.grug.minecraft.grug;

/**
 * Runs a block's {@code use} export for a loader and says whether the block consumed the click.
 *
 * <p>A {@code use} export returns nothing, so a block that has the export has handled the click
 * unless it calls {@code decline_use()}, which leaves the click to the game as if the block had no
 * {@code use} export, so the held item is used or placed as usual.
 */
public final class GrugBlockUse {
    private static boolean declined = false;

    private GrugBlockUse() {}

    /** Called by the {@code decline_use} host function. */
    static void decline() {
        declined = true;
    }

    /** Whether the block consumed the click: it has a {@code use} export and did not decline. */
    public static boolean use(
            long blockHandle, long worldId, double x, double y, double z, long playerId) {
        declined = false;
        boolean handled = ExportFns.Block_use(blockHandle, worldId, x, y, z, playerId);
        return handled && !declined;
    }
}
