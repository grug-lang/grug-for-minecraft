package net.grug.minecraft.core;

/**
 * Decides when a grug block entity that outlived its block is dropped (#163).
 *
 * <p>A client joined to a server is told only that a block changed, so the block entity of the
 * block that was there stays, and a chest placed over a grug block would be handed the grug block
 * entity. The loader drops it when the chunk is asked for it, except while a grug block runs its
 * own removal: there the block id has already changed, but the block entity is still the block's
 * own and {@code on_break} reads it.
 */
public final class GrugStaleBlockEntity {
    private static int removalDepth = 0;

    private GrugStaleBlockEntity() {}

    /** Runs a grug block's removal, during which {@link #shouldDrop} answers false. */
    public static void duringRemoval(Runnable removal) {
        removalDepth++;
        try {
            removal.run();
        } finally {
            removalDepth--;
        }
    }

    /**
     * Whether the block entity found at a position is stale: it is a grug block entity, the block
     * there is no grug block, and no grug block is removing itself.
     */
    public static boolean shouldDrop(boolean isGrugBlockEntity, boolean blockIsGrugBlock) {
        return isGrugBlockEntity && !blockIsGrugBlock && removalDepth == 0;
    }
}
