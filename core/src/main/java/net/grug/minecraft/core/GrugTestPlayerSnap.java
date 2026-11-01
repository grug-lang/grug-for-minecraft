package net.grug.minecraft.core;

/**
 * Where the test runner puts the player before every test, and when it does so at all.
 *
 * <p>The runner snaps the player to the centre of the block they stand in and levels their view
 * before the room is built, so every test starts with its player in a known place and looking the
 * same way, whatever the previous test left behind. The snap stays inside the player's own block,
 * and therefore inside the chunk already loaded and lit around them, because moving into a chunk
 * that is not lit yet crashes Alpha. See #609.
 *
 * <p>The arithmetic and the side check live here rather than in each loader so the five adapters
 * cannot drift apart, and so a unit test can reach both without a game.
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

    /**
     * Whether this side has a local player the runner can move. A dedicated server has none: its
     * tests run without a client, so the snap is skipped there rather than reported as a defect.
     */
    public static boolean canSnap(GrugSide side) {
        return side == GrugSide.CLIENT;
    }
}
