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
     * Whether the grug entity a block entity holds was created for another block than the one now
     * at its position, so it is the previous block's script with the previous block's members.
     *
     * <p>{@code initedEntityFileId} is the entity file the tile's grug entity was created from, and
     * {@link Grug#INVALID_GRUG_FILE_ID} before one was made. A block that names no entity file of
     * its own has nothing for the tile to hold either, so the entity the previous block made goes
     * rather than drawing that block's script for this one.
     */
    public static boolean shouldReinit(long initedEntityFileId, long blockEntityFileId) {
        return initedEntityFileId != Grug.INVALID_GRUG_FILE_ID
                && initedEntityFileId != blockEntityFileId;
    }
}
