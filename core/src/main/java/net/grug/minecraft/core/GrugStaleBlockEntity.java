package net.grug.minecraft.core;

import net.grug.minecraft.grug.Grug;

/**
 * Decides what happens to a grug block entity that outlived its block (#163).
 *
 * <p>A client joined to a server is told only that a block changed, so the block entity of the
 * block that was there stays, and a chest placed over a grug block would be handed the grug block
 * entity. The loader drops it when the chunk is asked for it, except while a grug block runs its
 * own removal: there the block id has already changed, but the block entity is still the block's
 * own and {@code on_break} reads it.
 *
 * <p>A grug block put in that place is another matter: the tile is the one the game keeps, and its
 * grug entity is the one the previous block's script made, so {@link #shouldReinit} says when the
 * render path has to make the entity the new block's own.
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

    /**
     * Whether a block entity that is not a grug one is stale on a joined client (#1354): the server
     * told the client only that a block changed, and the game never removes the block entity of the
     * block that was there, so a chest left by an earlier test stays in the client's chunk.
     *
     * <p>The chunk hands out whatever it holds before it looks at the block, so a grug block placed
     * where the chest was is handed the chest, and every read of the position (a block drawing as
     * its placeholder cube, a pipe seeing an inventory beside it) answers for a block that is gone.
     * It is stale when the block there is a grug block, or has no block entity at all. Only a
     * remote world is touched: on a server the game removes the entity in the old block's own
     * removal, which reads it after the id has changed.
     */
    public static boolean shouldDropForeign(
            boolean isGrugBlockEntity,
            boolean blockIsGrugBlock,
            boolean blockHasEntity,
            boolean remoteWorld) {
        return remoteWorld && !isGrugBlockEntity && (blockIsGrugBlock || !blockHasEntity);
    }

    /**
     * Whether the grug entity a block entity holds was attempted for another block than the one now
     * at its position, so it is the previous block's script with the previous block's members.
     *
     * <p>{@code attemptedEntityFileId} is the entity file the tile last tried to make its grug
     * entity from, and {@link Grug#INVALID_GRUG_FILE_ID} when that block names no entity file of
     * its own: the attempt is recorded even then, because a block that names no entity file
     * initializes the tile with nothing, and the chunk-mesh block placed on that tile afterwards
     * has to be inited from its own file rather than keeping the attempt.
     *
     * <p>An attempt equal to the block's file id is kept, including INVALID, so a creation that
     * failed is not retried on every render: only a different block re-inits the tile.
     */
    public static boolean shouldReinit(long attemptedEntityFileId, long blockEntityFileId) {
        return attemptedEntityFileId != blockEntityFileId;
    }
}
