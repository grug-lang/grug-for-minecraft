package net.grug.minecraft.core;

/**
 * Where the test runner puts the player before every test.
 *
 * <p>The runner snaps the player to the centre of the block they stand in and levels their view
 * before the room is built, so every test starts with its player in a known place and looking the
 * same way, whatever the previous test left behind. The snap stays inside the player's own block,
 * and therefore inside the chunk already loaded and lit around them, because moving into a chunk
 * that is not lit yet crashes Alpha. See #609.
 *
 * <p>Both sides snap, but not the same player: a client moves its own local player, while a
 * dedicated server moves its copy of the joined player and lets the teleport packet carry the
 * joined client's camera with it. A server that skipped the snap built its room from wherever the
 * client happened to stand, off the block grid a capture is framed against (#1161).
 *
 * <p>The arithmetic lives here rather than in each loader so the five adapters cannot drift apart,
 * and so a unit test can reach it without a game.
 */
public final class GrugTestPlayerSnap {
    private GrugTestPlayerSnap() {}

    /**
     * The coordinate the player is snapped to on one horizontal axis: the middle of the block they
     * stand in, so the room, the origin and a capture agree on where the player is.
     */
    public static double blockCentre(double coordinate) {
        return Math.floor(coordinate) + 0.5;
    }
}
