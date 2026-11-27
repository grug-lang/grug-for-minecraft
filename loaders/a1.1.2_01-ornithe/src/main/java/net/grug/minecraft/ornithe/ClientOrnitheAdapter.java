package net.grug.minecraft.ornithe;

import net.grug.minecraft.grug.GrugOwnedScreen;
import net.grug.minecraft.ornithe.client.GrugOwnedScreenHost;
import net.minecraft.client.Minecraft;
import net.ornithemc.osl.lifecycle.api.client.MinecraftInstance;

/**
 * The client's half of {@link OrnitheAdapter}: the calls that name the client's own screen classes.
 *
 * <p>{@link OrnitheAdapter} is loaded by the dedicated server too, as the superclass of {@link
 * ServerOrnitheAdapter}, and neither that class nor its base may mention a client-only game class
 * in a way the server's class loader resolves (#1246). This class is created only in the client
 * branch of {@link OrnitheAdapter#forCurrentEnvironment()}, so the server never loads it, and its
 * screen subclass ({@code GrugOwnedScreenHost}, which extends the client's {@code Screen}) stays
 * off the server's path.
 */
public class ClientOrnitheAdapter extends OrnitheAdapter {

    /**
     * The width the client's own font draws {@code text} at, which is what {@code
     * OverlayRenderContext.text_width} measures. A mod's screen centres its title with it, so a
     * loader without it cannot draw a screen the way the mod laid it out.
     */
    @Override
    public double textWidth(String text) {
        if (refuseWithoutAClient("OverlayRenderContext.text_width")) return 0.0;
        return MinecraftInstance.get().textRenderer.getWidth(text);
    }

    /**
     * Mod-owned screens are the screen subclass this loader adds, so a {@code Screen.new()} and
     * {@code Screen.open()} are legal here. The dedicated server answers false, in {@link
     * ServerOrnitheAdapter}, and has nothing to show.
     */
    @Override
    public boolean ownedScreensSupported() {
        return true;
    }

    @Override
    public void showOwnedScreen(GrugOwnedScreen screen) {
        // A test ticks on this client's own thread, so the screen is up, with its screen_init
        // asked, by the time this returns.
        MinecraftInstance.get().openScreen(new GrugOwnedScreenHost(screen));
    }

    @Override
    public void hideOwnedScreen(GrugOwnedScreen screen) {
        Minecraft minecraft = MinecraftInstance.get();
        // Only the screen this loader made for this mod screen is taken away: another screen the
        // player opened over it is theirs to keep. This version shows the world again where
        // another version shows an empty screen.
        if (minecraft.screen instanceof GrugOwnedScreenHost
                && ((GrugOwnedScreenHost) minecraft.screen).model() == screen) {
            minecraft.openScreen(null);
        }
    }
}
