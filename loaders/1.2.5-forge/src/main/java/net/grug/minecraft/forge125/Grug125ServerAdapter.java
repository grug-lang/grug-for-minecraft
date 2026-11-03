package net.grug.minecraft.forge125;

import net.grug.minecraft.core.GrugSide;
import net.grug.minecraft.grug.GrugGenerated;

import java.awt.image.BufferedImage;

/**
 * The adapter for a 1.2.5 dedicated server: everything that is not about which process this is
 * lives in {@link Grug125Adapter}, and what a server answers differently (no screens, no frames, no
 * sound, no capture) is here. The server has no client, so the client-only functions refuse
 * themselves through {@code ModLoaderAdapter.refuseWithoutAClient}.
 *
 * <p>Excluded from coverage because only the dispatch-only multiplayer job launches a server
 * (#166).
 */
@GrugGenerated("a dedicated server, which only the dispatch-only multiplayer job launches")
public class Grug125ServerAdapter extends Grug125Adapter {

    @Override
    public GrugSide getSide() {
        return GrugSide.DEDICATED_SERVER;
    }

    @Override
    public void openGui(Object playerObj, Object blockEntityObj, Object guiBuilderObj) {}

    @Override
    public Object getLevel() {
        return null;
    }

    @Override
    public Object testPlayer() {
        return null;
    }

    @Override
    public boolean hasSound(String path) {
        return false;
    }

    @Override
    public void playUiSound(String path, double volume, double pitch) {}

    @Override
    public void onFrameRendered() {}

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
    public BufferedImage captureScreenshot(double x1, double y1, double x2, double y2) {
        throw new IllegalStateException("A dedicated server has no screen to capture.");
    }

    @Override
    public void assertScreenshotEquals(
            String name, double x1, double y1, double x2, double y2, double tolerance) {
        refuseWithoutAClient("Screenshot.equals");
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

    @Override
    public void snapPlayerForTestOnClient() {}

    @Override
    public void restorePlayerAfterTest() {}
}
