package net.grug.minecraft.ornithe;

import net.grug.minecraft.core.GrugControlServer;
import net.grug.minecraft.core.GrugGameSpeed;
import net.grug.minecraft.core.GrugPlayerFloor;
import net.grug.minecraft.core.GrugSide;
import net.grug.minecraft.core.GrugTestRunner;
import net.grug.minecraft.grug.Grug;
import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.grug.GrugScreenshots;
import net.grug.minecraft.ornithe.control.GrugControlBeta;
import net.grug.minecraft.ornithe.mixin.WorldNotifyAccess;
import net.grug.minecraft.ornithe.server.GrugServerRun;
import net.minecraft.block.Block;
import net.minecraft.entity.mob.player.PlayerEntity;
import net.minecraft.world.World;
import net.ornithemc.osl.lifecycle.api.server.MinecraftServerInstance;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

/**
 * The adapter for a b1.7.3 dedicated server, which is the same loader as {@link OrnitheAdapter} in
 * a second process rather than a second mode.
 *
 * <p>It extends the client adapter rather than implementing {@link
 * net.grug.minecraft.core.ModLoaderAdapter} again, because everything that is not about which
 * process this is, is the same code: the block registry has no client in it, the recipe manager has
 * no client in it, and neither has any of the inventory or entity host functions. What a server
 * cannot inherit is the pair of handles, and that is all this replaces: the origin follows the
 * player {@link #testPlayer()} answers, so it is inherited too.
 *
 * <p>The client-only functions are inherited too, and are not overridden here: each one refuses
 * itself through {@code ModLoaderAdapter.refuseWithoutAClient}, which is what turns a screenshot
 * test on a server into an err naming the function, rather than an exception from inside {@code
 * net.minecraft.client}.
 *
 * <p>Excluded from coverage because only the dispatch-only multiplayer job launches a server, and
 * the coverage gate merges no server run's data (#167 stage 4). See #163.
 */
@GrugGenerated("a dedicated server, which only the dispatch-only multiplayer job launches")
public class ServerOrnitheAdapter extends OrnitheAdapter {

    @Override
    public GrugSide getSide() {
        return GrugSide.DEDICATED_SERVER;
    }

    /**
     * Queues the placement for the joined client. {@code setBlockQuietly} writes the chunk without
     * telling the world's listeners, and it is the listener that queues the change in the player
     * manager, so without this a dedicated server's placement never reaches the client. The notify
     * is the one {@code setBlock} does, without its neighbour updates. See #1078.
     */
    @Override
    protected void notifyPlacedBlock(World world, int x, int y, int z) {
        ((WorldNotifyAccess) world).grug$notifyBlockChanged(x, y, z);
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
     * player, so the choice only decides whose position {@code Test.get_origin} reports. The
     * inherited {@code testPlayerEye} and {@code getTestOrigin} both go through this answer, so
     * choosing the player here is all a server has to do. See #188.
     */
    @Override
    public Object testPlayer() {
        // Deliberately not List<ServerPlayerEntity>. Fabric Loader will not define a class the game
        // marked server-only in a client, so any reference the verifier has to resolve here would
        // break every client. A local variable's type argument is erased and so would survive, but
        // it promises a type the runtime cannot hand over: the next loop or cast written in this
        // method would break the client, and nothing at the call site would say why.
        // The player who started the run, when one has: forwarded calls use their screen (#1040).
        PlayerEntity starter = GrugControlBeta.starterPlayer();
        if (starter != null) {
            return starter;
        }
        List<?> players = MinecraftServerInstance.get().playerManager.players;
        return players.isEmpty() ? null : players.get(0);
    }

    /** Asked of the client of the player who started the run, with the world frozen (#1040). */
    @Override
    public boolean hasSound(String path) {
        try {
            return GrugControlBeta.server().hasSound(path);
        } catch (GrugControlServer.ControlException e) {
            Grug.hostFunctionErrorHappened(Grug.statePtr, "has_sound: " + e.getMessage());
            return false;
        }
    }

    /** Played by the client of the player who started the run, which owns the sound engine. */
    @Override
    public void playUiSound(String path, double volume, double pitch) {
        try {
            GrugControlBeta.server().playUiSound(path, volume, pitch);
        } catch (GrugControlServer.ControlException e) {
            Grug.hostFunctionErrorHappened(Grug.statePtr, "play_ui_sound: " + e.getMessage());
        }
    }

    /**
     * Forwards the capture to the joined client of the player who started the run and waits for the
     * answer with the world frozen (#1040, option A). Arguments are checked first, as on a client.
     */
    @Override
    public BufferedImage captureScreenshot(double x1, double y1, double x2, double y2) {
        String bad = GrugScreenshots.rectangleProblem(x1, y1, x2, y2);
        if (bad != null) {
            Grug.hostFunctionErrorHappened(Grug.statePtr, "Screenshot.capture: " + bad);
            return null;
        }
        try {
            return GrugControlBeta.server().capture((int) x1, (int) y1, (int) x2, (int) y2);
        } catch (GrugControlServer.ControlException e) {
            Grug.hostFunctionErrorHappened(Grug.statePtr, "Screenshot.capture: " + e.getMessage());
            return null;
        }
    }

    /** This server forwards a test's client-only calls to the player who started the run. */
    @Override
    public boolean forwardsClientCalls() {
        return true;
    }

    /** Nothing to close: the screen belongs to a client, and a server has none. */
    @Override
    public void closeOpenScreenForTest() {}

    /** No screen can be open on a server. */
    @Override
    public boolean isScreenOpenForTest() {
        return false;
    }

    /**
     * Ready once the world around the player is loaded, the player is out of any block and the
     * player has landed. The test origin is read from the player's position, and a player still
     * falling when the first test starts gives an origin whose height differs from run to run,
     * which put one test's blocks above the world's height (#1052).
     *
     * <p>{@code onGround} alone is not enough. The server copies it from the client's movement
     * packets, and a client that fell through the terrain while its chunks were arriving reports a
     * position a fraction of a block inside the ground, which the server accepts because the box
     * its collision check tests is contracted by 0.0625. From there the client's own collision
     * never reports support, and every later packet takes the handler's teleport-back path, which
     * returns before {@code onGround} is copied, so the wait timed out with the client in the world
     * and the run already started (#1095). The server's own world has the ground, so the player
     * counts as ready when they stand on it, and a player inside a block is never accepted.
     */
    @Override
    public boolean isWorldReady(Object playerObj) {
        PlayerEntity player = (PlayerEntity) playerObj;
        double feet = feetY(player);
        GrugPlayerFloor.BlockSource blocks = solidBlocks(getLevel());
        return super.isWorldReady(playerObj)
                && !GrugPlayerFloor.insideABlock(blocks, player.x, feet, player.z)
                && (player.onGround
                        || GrugPlayerFloor.standsOn(
                                feet, GrugPlayerFloor.floorTop(blocks, player.x, feet, player.z)));
    }

    /**
     * The solid blocks of a level, as {@link GrugPlayerFloor} reads them. A block counts when it
     * stops movement, which is the test the level's own surface height uses, so a torch or a plant
     * under the player is not a floor.
     */
    public static GrugPlayerFloor.BlockSource solidBlocks(Object levelObj) {
        World world = (World) levelObj;
        return (x, y, z) -> {
            int id = world.getBlock(x, y, z);
            return id != 0 && Block.BY_ID[id].material.blocksMovement();
        };
    }

    /**
     * Asks the client of the player who started the run whether it has drawn the server's world,
     * with the world frozen, and answers the same to the test (#1086). A server on its own is
     * settled as soon as a test asks, because no client frame or block packet is owed to anyone;
     * that is also the answer when no client has started a run.
     */
    @Override
    public boolean isWorldSettled() {
        if (!GrugControlBeta.server().hasStarter()) {
            return true;
        }
        try {
            return GrugControlBeta.server().worldSettled();
        } catch (GrugControlServer.ControlException e) {
            Grug.hostFunctionErrorHappened(Grug.statePtr, "Test.world_settled: " + e.getMessage());
            return true;
        }
    }

    /** The server has no frames, so there is nothing to forget. */
    @Override
    public void forgetSettledFrames() {}

    /**
     * The feet are the bottom of the bounding box, rounded to a millionth to shed the arithmetic's
     * last bits: the server's player keeps the feet in {@code y}, so the client's eye offset built
     * the test room's floor a block and a half too low and dropped the player out of the world
     * (#163). A client's box is not used because it sits {@code ySize} above the feet for a few
     * ticks after a step-up, a respawn or a teleport.
     */
    @Override
    protected double feetY(PlayerEntity player) {
        return Math.rint(player.shape.minY * 1e6) / 1e6;
    }

    /** The tests listed in {@code server-unsupported-tests.txt}, which says why. */
    @Override
    public Set<String> serverUnsupportedTests() {
        try (InputStream in =
                ServerOrnitheAdapter.class.getResourceAsStream("/server-unsupported-tests.txt")) {
            if (in == null) {
                throw new IllegalStateException(
                        "server-unsupported-tests.txt is not in the layer.");
            }
            return GrugTestRunner.parseTestList(new String(readAll(in), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Could not read server-unsupported-tests.txt.", e);
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int n;
        while ((n = in.read(buffer)) > 0) {
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    /** The world's own tick, as on the other loaders, so a test need not wait for the server. */
    @Override
    public int tickLevel(Object levelObj, int ticks) {
        return GrugServerRun.advance((World) levelObj, ticks);
    }

    /** The client's cap of ticks for one frame, which the server takes as extra ticks per tick. */
    @Override
    public double maxGameSpeed() {
        return CLIENT_TICKS_PER_FRAME;
    }

    @Override
    public boolean setGameSpeed(double speed) {
        GrugServerRun.setGameSpeed(GrugGameSpeed.wholeSteps(speed));
        return true;
    }
}
