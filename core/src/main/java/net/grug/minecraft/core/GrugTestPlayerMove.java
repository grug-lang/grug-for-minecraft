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
}
