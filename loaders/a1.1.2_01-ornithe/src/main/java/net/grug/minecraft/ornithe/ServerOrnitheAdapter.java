package net.grug.minecraft.ornithe;

import net.grug.minecraft.core.GrugControlServer;
import net.grug.minecraft.core.GrugSide;
import net.grug.minecraft.core.GrugTestRunner;
import net.grug.minecraft.grug.Grug;
import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.grug.GrugScreenshots;
import net.grug.minecraft.ornithe.control.GrugControlAlpha;
import net.grug.minecraft.ornithe.server.GrugServerRun;
import net.minecraft.entity.mob.player.PlayerEntity;
import net.minecraft.entity.mob.player.PlayerInventory;
import net.minecraft.world.World;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
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
 * net.minecraft.client}.
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
        List<?> players = world.players;
        return players.isEmpty() ? null : players.get(0);
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
     * The plain-tick tests the run asks a dedicated server to run (#1040), from the comma separated
     * {@code GRUG_CI_FORWARD_TESTS}: their client-only calls are forwarded.
     */
    @Override
    public Set<String> forwardedTestsOnServer() {
        Set<String> paths = new LinkedHashSet<>();
        String value = System.getenv("GRUG_CI_FORWARD_TESTS");
        if (value != null) {
            for (String path : value.split(",")) {
                if (!path.trim().isEmpty()) paths.add(path.trim());
            }
        }
        return paths;
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

    /** Settled as soon as a test asks: no client frame or block packet is owed to anyone. */
    @Override
    public boolean isWorldSettled() {
        return true;
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
