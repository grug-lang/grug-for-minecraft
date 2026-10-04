package net.grug.minecraft.ornithe;

import net.grug.minecraft.core.GrugSide;
import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.grug.Vec3;
import net.minecraft.entity.mob.player.PlayerEntity;
import net.ornithemc.osl.lifecycle.api.server.MinecraftServerInstance;

import java.util.List;

/**
 * The adapter for a b1.7.3 dedicated server, which is the same loader as {@link OrnitheAdapter} in
 * a second process rather than a second mode.
 *
 * <p>It extends the client adapter rather than implementing {@link
 * net.grug.minecraft.core.ModLoaderAdapter} again, because everything that is not about which
 * process this is, is the same code: the block registry has no client in it, the recipe manager has
 * no client in it, and neither has any of the inventory or entity host functions. What a server
 * cannot inherit is the pair of handles and the origin that follows from one of them, and that is
 * all this replaces.
 *
 * <p>The client-only functions are inherited too, and are not overridden here: each one refuses
 * itself through {@code ModLoaderAdapter.refuseWithoutAClient}, which is what turns a screenshot
 * test on a server into an err naming the function, rather than an exception from inside {@code
 * net.minecraft.client}.
 *
 * <p>Whole class is excluded from coverage until CI launches a server: see #163, which is the
 * change that runs this class and takes it back off the list.
 */
@GrugGenerated("a dedicated server, which no CI run launches until #163")
public class ServerOrnitheAdapter extends OrnitheAdapter {

    @Override
    public GrugSide getSide() {
        return GrugSide.DEDICATED_SERVER;
    }

    @Override
    public Object getLevel() {
        return MinecraftServerInstance.get().getWorld(0);
    }

    /**
     * The first player who joined.
     *
     * <p>A server has no local player, so a test that wants one to build fixtures around and to
     * right-click with has to be given one of the connected players. Which one does not matter: the
     * tests share one world, and they are told to build in bands above the origin rather than at a
     * player, so the choice only decides whose position {@code Test.get_origin} reports.
     */
    @Override
    public Object testPlayer() {
        // Deliberately not List<ServerPlayerEntity>. Fabric Loader will not define a class the game
        // marked server-only in a client, so any reference the verifier has to resolve here would
        // break every client. A local variable's type argument is erased and so would survive, but
        // it promises a type the runtime cannot hand over: the next loop or cast written in this
        // method would break the client, and nothing at the call site would say why.
        List<?> players = MinecraftServerInstance.get().playerManager.players;
        return players.isEmpty() ? null : players.get(0);
    }

    /**
     * The player's position plus 3, which is what the client adapter does and therefore what the
     * bands the tests build in mean on a server too.
     *
     * <p>Null rather than an exception when nobody has joined, because {@link #testPlayer()}
     * promises null for that case and this must not be the place that contradicts it. The runner
     * has no reason to start before somebody is connected, so this is what a run that did anyway
     * would report.
     */
    @Override
    public Vec3 getTestOrigin() {
        PlayerEntity player = (PlayerEntity) testPlayer();
        if (player == null) return null;

        return new Vec3(player.x, player.y + 3.0, player.z);
    }
}
