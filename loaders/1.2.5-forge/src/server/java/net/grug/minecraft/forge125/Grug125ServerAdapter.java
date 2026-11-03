package net.grug.minecraft.forge125;

import net.grug.minecraft.core.GrugControlServer;
import net.grug.minecraft.core.GrugGameSpeed;
import net.grug.minecraft.core.GrugSide;
import net.grug.minecraft.core.GrugTestRunner;
import net.grug.minecraft.forge125.control.GrugControlServer125;
import net.grug.minecraft.grug.Grug;
import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.grug.GrugScreenshots;
import net.grug.minecraft.grug.Vec3;
import net.minecraft.src.EntityPlayer;

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
 * client-only functions refuse themselves through {@code ModLoaderAdapter.refuseWithoutAClient}.
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

    @Override
    public boolean hasSound(String path) {
        return false;
    }

    @Override
    public void playUiSound(String path, double volume, double pitch) {}

    @Override
    public void onFrameRendered() {}

    /** Settled as soon as a test asks: no client frame or block packet is owed to anyone. */
    @Override
    public boolean isWorldSettled() {
        return true;
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

    @Override
    public void useBlockForTest(Object levelObj, double x, double y, double z) {
        refuseWithoutAClient("Test.use_block");
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

    @Override
    public void assertScreenshotEquals(
            String name, double x1, double y1, double x2, double y2, double tolerance) {
        refuseWithoutAClient("Screenshot.equals");
    }
}
