package net.grug.minecraft.ornithe;

import net.grug.minecraft.core.GrugControlServer;
import net.grug.minecraft.core.GrugSide;
import net.grug.minecraft.core.GrugTestRunner;
import net.grug.minecraft.core.GrugUseBlockFace;
import net.grug.minecraft.grug.Grug;
import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.grug.GrugScreenshots;
import net.grug.minecraft.ornithe.block.GrugBlock;
import net.grug.minecraft.ornithe.control.GrugControlAlpha;
import net.grug.minecraft.ornithe.mixin.WorldNotifyAccess;
import net.grug.minecraft.ornithe.server.GrugServerRun;
import net.minecraft.block.Block;
import net.minecraft.entity.mob.player.PlayerEntity;
import net.minecraft.entity.mob.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

/**
 * The adapter for an Alpha a0.2.1 dedicated server, which is the same loader as {@link
 * OrnitheAdapter} in a second process rather than a second mode.
 *
 * <p>It extends the client adapter, because everything that is not about which process this is, is
 * the same code: the block registry, the inventory and the entity host functions name no client
 * class. What a server cannot inherit is the pair of handles, the screen and the player snap, and
 * those are all this replaces. The client-only functions are inherited and not overridden: each one
 * refuses itself through {@code ModLoaderAdapter.refuseWithoutAClient}, which turns a screenshot
 * test on a server into an err naming the function instead of an error from inside {@code
 * net.minecraft.client}. {@code useBlockForTest} is not one of them and is overridden: a click is
 * not a client-only function, so the server right-clicks in its own world, the world the test reads
 * (#1090).
 *
 * <p>A dedicated server has no {@code MinecraftInstance}, so the world comes from the one the
 * server run saw tick ({@link GrugServerRun#world()}), and the players are that world's.
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
        return GrugServerRun.world();
    }

    /**
     * The first player who joined. A server has no local player, so a test that wants one to build
     * fixtures around has to be given one of the connected players. Which one does not matter: the
     * tests share one world and build in bands above the origin.
     */
    @Override
    public Object testPlayer() {
        World world = GrugServerRun.world();
        if (world == null) {
            return null;
        }
        // The player who started the run, when one has: it is their screen the forwarded calls use.
        PlayerEntity starter = GrugControlAlpha.starterPlayer();
        if (starter != null) {
            return starter;
        }
        List<?> players = world.players;
        return players.isEmpty() ? null : players.get(0);
    }

    /**
     * Right-clicks a block on this server's own world, so a server-run test's click lands in the
     * world the test reads (#1090).
     *
     * <p>A forwarded click would deadlock: the client's interaction manager sends a use packet the
     * server thread is blocked inside this call and so cannot process. So the click is made here
     * instead, in the same order the game's server uses for a client's use packet: the block's
     * {@code use} first, and the held item's {@code useOn} when the block declines.
     *
     * <p>The a0.2.1 server has no {@code ServerPlayerInteractionManager} to call: this generation's
     * client jar, which is what this layer compiles against, does not hold one either, so the two
     * calls its {@code useBlock} makes are made here in the same order.
     *
     * <p>The face is the one a ray from the test player's eye to the block's centre enters, which
     * is the face the player sees of it: the top of a block below the eye, the bottom of one above
     * it, and the side facing the player of one at its level. The client path asks {@link
     * GrugUseBlockFace} with the same eye, so both sides click the same face for the same fixture
     * (#1106). Alpha's {@code Block.use} takes no face, so it is the held item's {@code useOn} that
     * sees it.
     */
    @Override
    public void useBlockForTest(Object levelObj, double x, double y, double z) {
        World world = (World) levelObj;
        PlayerEntity player = (PlayerEntity) testPlayer();
        if (player == null) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr, "Test.use_block: There is no player to right-click with.");
            return;
        }

        int blockX = (int) Math.floor(x);
        int blockY = (int) Math.floor(y);
        int blockZ = (int) Math.floor(z);

        // In Alpha, World.getBlock() hands back a block id rather than a Block.
        int blockId = GrugWorldAccess.blockId(world, blockX, blockY, blockZ);
        Block block = (blockId >= 0 && blockId < Block.BY_ID.length) ? Block.BY_ID[blockId] : null;

        if (!(block instanceof GrugBlock)) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr,
                    "Test.use_block: There is no grug block at "
                            + blockX
                            + ", "
                            + blockY
                            + ", "
                            + blockZ
                            + ".");
            return;
        }

        if (!block.use(world, blockX, blockY, blockZ, player)) {
            ItemStack held = player.inventory.getSelectedItem();
            if (held != null) {
                held.useOn(
                        player,
                        world,
                        blockX,
                        blockY,
                        blockZ,
                        GrugUseBlockFace.face(testPlayerEye(player), blockX, blockY, blockZ));
            }
        }
    }

    /** Asked of the client of the player who started the run, with the world frozen (#1040). */
    @Override
    public boolean hasSound(String path) {
        try {
            return GrugControlAlpha.server().hasSound(path);
        } catch (GrugControlServer.ControlException e) {
            Grug.hostFunctionErrorHappened(Grug.statePtr, "has_sound: " + e.getMessage());
            return false;
        }
    }

    /** Played by the client of the player who started the run, which owns the sound engine. */
    @Override
    public void playUiSound(String path, double volume, double pitch) {
        try {
            GrugControlAlpha.server().playUiSound(path, volume, pitch);
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
            return GrugControlAlpha.server().capture((int) x1, (int) y1, (int) x2, (int) y2);
        } catch (GrugControlServer.ControlException e) {
            Grug.hostFunctionErrorHappened(Grug.statePtr, "Screenshot.capture: " + e.getMessage());
            return null;
        }
    }

    /**
     * This server forwards a test's client-only calls to the player who started the run (#1040).
     */
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
     * Ready once the world around the player is loaded and the player has landed. The test origin
     * is read from the player's position, and a player still falling when the first test starts
     * gives an origin whose height differs from run to run, which put one test's blocks above the
     * world's height (#1052).
     */
    @Override
    public boolean isWorldReady(Object playerObj) {
        return super.isWorldReady(playerObj) && ((PlayerEntity) playerObj).onGround;
    }

    /**
     * Asks the client of the player who started the run whether it has drawn the server's world,
     * with the world frozen, and answers the same to the test (#1086). A server on its own is
     * settled as soon as a test asks, because no client frame or block packet is owed to anyone;
     * that is also the answer when no client has started a run.
     */
    @Override
    public boolean isWorldSettled() {
        if (!GrugControlAlpha.server().hasStarter()) {
            return true;
        }
        try {
            return GrugControlAlpha.server().worldSettled();
        } catch (GrugControlServer.ControlException e) {
            Grug.hostFunctionErrorHappened(Grug.statePtr, "Test.world_settled: " + e.getMessage());
            return true;
        }
    }

    /** The server has no frames, so there is nothing to forget. */
    @Override
    public void forgetSettledFrames() {}

    /**
     * The server's {@code Inventory} interface has no {@code setItem}, which the client's has, so
     * the call is made on {@code PlayerInventory} itself, which declares it on both.
     */
    @Override
    public void clearInventoryOf(Object playerObj) {
        PlayerEntity player = (PlayerEntity) playerObj;
        PlayerInventory inventory = player.inventory;
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, null);
        }
        inventory.selectedSlot = 0;
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
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
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
        GrugServerRun.setGameSpeed((int) speed);
        return true;
    }
}
