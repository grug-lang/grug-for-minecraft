package net.grug.minecraft.core;

/**
 * Whether a test may move the player to a position it asked for.
 *
 * <p>{@code TestTickContext.set_player_position} moves the player only when the chunk there has
 * been received, because a teleport into a chunk the client has not received crashes the versions
 * before 1.3. The loader reads the three facts the game offers (is there a player, is there a
 * world, has that chunk arrived) and this decides from them, so a unit test covers every
 * combination without a game and no loader carries a comparison whose other side the run never
 * reaches. See {@link GrugWorldReady#spawnsAllowed(int)} for the same shape, and #1427.
 */
public final class GrugTestPlayerMove {
    private GrugTestPlayerMove() {}

    /**
     * @param hasPlayer whether this side has a player to move
     * @param hasWorld whether this side has a world to move them in
     * @param chunkLoaded whether the client has received the chunk the target falls in
     */
    public static boolean canMove(boolean hasPlayer, boolean hasWorld, boolean chunkLoaded) {
        return hasPlayer && hasWorld && chunkLoaded;
    }

    /**
     * Whether a test may move the player, from the game's own objects: there is a {@code player}
     * and a {@code world}, and {@code chunkLoaded} says the world has the target's chunk.
     */
    public static <W> boolean canMoveTo(
            Object player, W world, java.util.function.Predicate<W> chunkLoaded) {
        return canMove(player != null, world != null, world != null && chunkLoaded.test(world));
    }

    /**
     * Whether the client has received a chunk. Beta 1.7.3's {@code MultiplayerChunkCache.hasChunk}
     * begins with {@code if (this != null) return true}, so {@code isChunkLoaded} answers true for
     * every chunk on a multiplayer client, and the chunk cache answers the shared empty chunk for
     * one that never arrived. A single-player world answers {@code isChunkLoaded} truthfully, so
     * asking for both covers either kind of client (#1507).
     *
     * @param loaded what {@code isChunkLoaded} said, which is not enough on a multiplayer client
     * @param empty whether the chunk the cache hands back is the empty placeholder
     */
    public static boolean chunkReceived(boolean loaded, boolean empty) {
        return loaded && !empty;
    }
}
