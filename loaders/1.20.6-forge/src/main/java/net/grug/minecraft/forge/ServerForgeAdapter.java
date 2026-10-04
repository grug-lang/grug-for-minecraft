package net.grug.minecraft.forge;

import net.grug.minecraft.core.GrugSide;
import net.grug.minecraft.grug.GrugGenerated;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.server.ServerLifecycleHooks;

/**
 * The adapter for a 1.20.6 dedicated server, which is the same loader as {@link ForgeAdapter} in a
 * second process rather than a second mode.
 *
 * <p>It extends the client adapter rather than implementing {@link
 * net.grug.minecraft.core.ModLoaderAdapter} again, because everything that is not about which
 * process this is, is the same code: the registries have no client in them, the recipe manager has
 * no client in it, and neither has any of the inventory or entity host functions.
 *
 * <p>Only the side and the player are replaced. {@code getLevel} and {@code getTestOrigin} are not,
 * because the client adapter already prefers the running server's world and its first player when
 * there is one, and a dedicated server always has both.
 *
 * <p>The client-only functions are inherited too, and are not overridden here: each one refuses
 * itself through {@code ModLoaderAdapter.refuseWithoutAClient}, which is what turns a screenshot
 * test on a server into an err naming the function, rather than an exception from inside {@code
 * net.minecraft.client}.
 *
 * <p>Whole class is excluded from coverage until CI launches a server: see #167.
 */
@GrugGenerated("a dedicated server, which no CI run launches until #167")
public class ServerForgeAdapter extends ForgeAdapter {

    @Override
    public GrugSide getSide() {
        return GrugSide.DEDICATED_SERVER;
    }

    /**
     * The first player who joined.
     *
     * <p>A server has no local player, so a test that wants one to build fixtures around and to
     * right-click with has to be given one of the connected players. Which one does not matter: the
     * tests share one world and build in bands above the origin, so the choice only decides whose
     * position {@code Test.get_origin} reports.
     */
    @Override
    public Object testPlayer() {
        var players = ServerLifecycleHooks.getCurrentServer().getPlayerList().getPlayers();
        return players.isEmpty() ? null : (ServerPlayer) players.get(0);
    }
}
