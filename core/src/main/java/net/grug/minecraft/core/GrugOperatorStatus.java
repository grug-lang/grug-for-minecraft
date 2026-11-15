package net.grug.minecraft.core;

/**
 * What an adapter answers when a player is an operator, for the part of that answer no game keeps:
 * the owner of a singleplayer world (#1331).
 *
 * <p>A dedicated server keeps a list of operators ({@code ops.txt}, or the 1.20.6 operator list),
 * and a player is an operator exactly when the list holds them. A singleplayer world has no such
 * rule for its owner: a loader with no integrated server (Alpha, Beta and 1.2.5) has no list at
 * all, and 1.20.6 makes the owner an operator only while the world allows cheats. Yet the owner of
 * their own world must count as an operator, or a feature that asks "may this player do operator
 * things" would refuse the one person who can do anything there.
 *
 * <p>So the owner is an operator unless a script revoked it, and that revoke has to be readable
 * back, which no game state can carry: it is the one bit this class keeps for the process. Granting
 * clears it, and a listed player is an operator whatever it says. A player who is not the owner
 * never reads it.
 */
public final class GrugOperatorStatus {
    /**
     * The permission level from which a player may use cheats: the reference JEI's {@code
     * hasPermissionForCheatMode} needs level 2, the game's own gamemaster level. A level-1 entry of
     * the operator list (a player who can only bypass spawn protection) is not an operator here.
     */
    public static final int CHEAT_LEVEL = 2;

    /** Whether an entry of the operator list at {@code level} is an operator for #1331 and #882. */
    public static boolean levelMayCheat(int level) {
        return level >= CHEAT_LEVEL;
    }

    private volatile boolean ownerRevoked;

    /**
     * Whether a player is an operator.
     *
     * @param owner whether the player is the owner of a singleplayer world
     * @param listed whether the game's own operator list holds the player, which a loader with no
     *     list passes as false
     */
    public boolean isOperator(boolean owner, boolean listed) {
        return listed || (owner && !ownerRevoked);
    }

    /** Records that the player was made an operator, so the owner reads as one again. */
    public void granted() {
        ownerRevoked = false;
    }

    /** Records that the player lost operator status, so the owner no longer reads as one. */
    public void revoked() {
        ownerRevoked = true;
    }
}
