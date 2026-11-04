package net.grug.minecraft.forge125;

import net.grug.minecraft.core.GrugControlServer;
import net.grug.minecraft.core.GrugGameSpeed;
import net.grug.minecraft.core.GrugSide;
import net.grug.minecraft.core.GrugTestRunner;
import net.grug.minecraft.core.GrugUseBlockFace;
import net.grug.minecraft.forge125.control.GrugControlServer125;
import net.grug.minecraft.grug.Grug;
import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.grug.GrugScreenshots;
import net.grug.minecraft.grug.Vec3;
import net.minecraft.src.EntityPlayer;
import net.minecraft.src.EntityPlayerMP;
import net.minecraft.src.World;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * The adapter for a 1.2.5 dedicated server: everything that is not about which process this is
 * lives in {@link Grug125Adapter}, and what a server answers differently (the level and the player
 * handles, no screens, no frames, no sound, no capture) is here. The server has no client, so the
 * client-only functions refuse themselves through {@code ModLoaderAdapter.refuseWithoutAClient};
 * {@code useBlockForTest} is not one of them and is overridden, so the server right-clicks in its
 * own world, the world the test reads (#1090).
 *
 * <p>Excluded from coverage because only the dispatch-only multiplayer job launches a server
 * (#166).
 */
@GrugGenerated("a dedicated server, which only the dispatch-only multiplayer job launches")
public class Grug125ServerAdapter extends Grug125Adapter {

    /** A player's eye is this far above their feet. */
    private static final double EYE_HEIGHT = 1.62;

    @Override
    public GrugSide getSide() {
        return GrugSide.DEDICATED_SERVER;
    }

    @Override
    public void openGui(Object playerObj, Object blockEntityObj, Object guiBuilderObj) {
        refuseWithoutAClient("GUI.open");
    }

    // The screen functions a client adapter implements, refused here rather than inherited, so a
    // server says it has no client instead of saying this loader cannot drive a screen: 1.2.5's
    // server adapter does not extend the client adapter, so it would otherwise answer with the
    // interface's default.

    @Override
    public void openInventory(Object playerObj) {
        refuseWithoutAClient("Test.open_inventory");
    }

    @Override
    public boolean pressScreenKey(int key, boolean ctrl, boolean release) {
        refuseWithoutAClient("Test.press_screen_key");
        return false;
    }

    @Override
    public boolean scrollScreen(double x, double y, double delta) {
        refuseWithoutAClient("Test.scroll_screen");
        return false;
    }

    @Override
    public boolean clickScreen(double x, double y, double button) {
        refuseWithoutAClient("Test.click_screen");
        return false;
    }

    @Override
    public double screenWidthForTest() {
        refuseWithoutAClient("Test.screen_width");
        return 0.0;
    }

    @Override
    public double guiLeftForTest() {
        refuseWithoutAClient("Test.gui_left");
        return 0.0;
    }

    @Override
    public double guiWidthForTest() {
        refuseWithoutAClient("Test.gui_width");
        return 0.0;
    }

    @Override
    public double guiTopForTest() {
        refuseWithoutAClient("Test.gui_top");
        return 0.0;
    }

    @Override
    public double guiHeightForTest() {
        refuseWithoutAClient("Test.gui_height");
        return 0.0;
    }

    @Override
    public Object getLevel() {
        return GrugServerRun.world();
    }

    /**
     * The first player who joined. A server has no local player, so a test that wants one to build
     * fixtures around is given a connected one: the tests share one world and build in a room
     * around the test player, so which one only decides whose position {@code Test.get_origin}
     * reports.
     */
    @Override
    public Object testPlayer() {
        // The player who started the run, when one has: forwarded calls use their screen (#1040).
        EntityPlayerMP starter = GrugControlServer125.starterPlayer();
        return starter != null ? starter : GrugServerRun.firstPlayer();
    }

    /**
     * The eye of the test player. A server player keeps the feet in {@code posY} where the client
     * keeps the eye, so the eye is rebuilt from the bottom of the bounding box, rounded to a
     * millionth to shed the arithmetic's last bits, which is what the client's {@code posY} is.
     */
    @Override
    public Vec3 testPlayerEye(Object playerObj) {
        EntityPlayer player = (EntityPlayer) playerObj;
        return new Vec3(
                player.posX,
                Math.rint(player.boundingBox.minY * 1e6) / 1e6 + EYE_HEIGHT,
                player.posZ);
    }

    /**
     * Ready once the world around the player is loaded and the player has landed: the test origin
     * is read from the player's position, and a player still falling when the first test starts
     * gives an origin whose height differs from run to run (#1052).
     */
    @Override
    public boolean isWorldReady(Object playerObj) {
        return super.isWorldReady(playerObj) && ((EntityPlayer) playerObj).onGround;
    }

    /** Asked of the client of the player who started the run, with the world frozen (#1040). */
    @Override
    public boolean hasSound(String path) {
        try {
            return GrugControlServer125.server().hasSound(path);
        } catch (GrugControlServer.ControlException e) {
            Grug.hostFunctionErrorHappened(Grug.statePtr, "has_sound: " + e.getMessage());
            return false;
        }
    }

    /** Played by the client of the player who started the run, which owns the sound engine. */
    @Override
    public void playUiSound(String path, double volume, double pitch) {
        try {
            GrugControlServer125.server().playUiSound(path, volume, pitch);
        } catch (GrugControlServer.ControlException e) {
            Grug.hostFunctionErrorHappened(Grug.statePtr, "play_ui_sound: " + e.getMessage());
        }
    }

    @Override
    public void onFrameRendered() {}

    /**
     * Asks the client of the player who started the run whether it has drawn the server's world,
     * with the world frozen, and answers the same to the test (#1086). A server on its own is
     * settled as soon as a test asks, because no client frame or block packet is owed to anyone;
     * that is also the answer when no client has started a run.
     */
    @Override
    public boolean isWorldSettled() {
        if (!GrugControlServer125.server().hasStarter()) {
            return true;
        }
        try {
            return GrugControlServer125.server().worldSettled();
        } catch (GrugControlServer.ControlException e) {
            Grug.hostFunctionErrorHappened(Grug.statePtr, "Test.world_settled: " + e.getMessage());
            return true;
        }
    }

    @Override
    public void forgetSettledFrames() {}

    @Override
    public void closeOpenScreenForTest() {}

    @Override
    public boolean isScreenOpenForTest() {
        return false;
    }

    @Override
    public boolean setGameSpeed(double speed) {
        GrugServerRun.setGameSpeed(GrugGameSpeed.wholeSteps(speed));
        return true;
    }

    @Override
    public void snapPlayerForTestOnClient() {}

    @Override
    public void restorePlayerAfterTest() {}

    /**
     * The tests listed in {@code forge125-server-unsupported-tests.txt}, which says why. The name
     * carries the loader because this layer is at the universal jar's root, and a root file of the
     * bare name would shadow the nested layers' own lists on the class path.
     */
    @Override
    public Set<String> serverUnsupportedTests() {
        try (InputStream in =
                Grug125ServerAdapter.class.getResourceAsStream(
                        "/forge125-server-unsupported-tests.txt")) {
            if (in == null) {
                throw new IllegalStateException(
                        "forge125-server-unsupported-tests.txt is not in the jar.");
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
            }
            return GrugTestRunner.parseTestList(
                    new String(out.toByteArray(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Could not read forge125-server-unsupported-tests.txt.", e);
        }
    }

    @Override
    public boolean miningCrackForTest(Object levelObj, double x, double y, double z) {
        refuseWithoutAClient("Test.mining_crack");
        return false;
    }

    /**
     * Right-clicks a block on this server's own world, so a server-run test's click lands in the
     * world the test reads (#1090).
     *
     * <p>A forwarded click would deadlock: the client's {@code PlayerControllerMP} sends a use
     * packet the server thread is blocked inside this call and so cannot process. So the click is
     * made here instead, through the server's own interaction manager, {@code ItemInWorldManager},
     * which is what {@code NetServerHandler.handlePlace} reaches for a client's use packet: the
     * block's activation first, and the held item's use against it when the block declines.
     *
     * <p>The face is the one a ray from the test player's eye to the block's centre enters, which
     * is the face the player sees of it: the top of a block below the eye, the bottom of one above
     * it, and the side facing the player of one at its level. The client path asks {@link
     * GrugUseBlockFace} with the same eye, so both sides click the same face for the same fixture
     * (#1106).
     */
    @Override
    public void useBlockForTest(Object levelObj, double x, double y, double z) {
        World world = (World) levelObj;
        EntityPlayerMP player = (EntityPlayerMP) testPlayer();
        if (player == null) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr, "Test.use_block: There is no player to right-click with.");
            return;
        }

        int blockX = (int) Math.floor(x);
        int blockY = (int) Math.floor(y);
        int blockZ = (int) Math.floor(z);

        if (blockAt(world, blockX, blockY, blockZ) == null) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr,
                    "Test.use_block: There is no block at "
                            + blockX
                            + ", "
                            + blockY
                            + ", "
                            + blockZ
                            + ".");
            return;
        }

        player.itemInWorldManager.activeBlockOrUseItem(
                player,
                world,
                player.inventory.getCurrentItem(),
                blockX,
                blockY,
                blockZ,
                GrugUseBlockFace.face(testPlayerEye(player), blockX, blockY, blockZ));
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
            return GrugControlServer125.server().capture((int) x1, (int) y1, (int) x2, (int) y2);
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
}
