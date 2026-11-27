package net.grug.minecraft.stationapi;

import net.fabricmc.loader.api.FabricLoader;
import net.grug.minecraft.grug.GrugOwnedScreen;
import net.grug.minecraft.stationapi.client.GrugOwnedScreenHost;
import net.minecraft.client.Minecraft;

/**
 * The client's half of {@link StationApiAdapter}: the calls that name the client's own screen
 * classes.
 *
 * <p>{@link StationApiAdapter} is loaded by the dedicated server too, as the superclass of {@link
 * ServerStationApiAdapter}, and neither that class nor its base may mention a client-only game
 * class in a way the server's class loader resolves: StationAPI refuses to define the client's
 * {@code Screen} in a server process (#1246). This class is created only in the client branch of
 * {@link ServerAdapterChoice#forThisSide()}, so the server never loads it, and its screen subclass
 * ({@code GrugOwnedScreenHost}, which extends the client's {@code Screen}) stays off the server's
 * path.
 */
public class ClientStationApiAdapter extends StationApiAdapter {

    /**
     * The width the client's own font draws {@code text} at, which is what {@code
     * OverlayRenderContext.text_width} measures. A mod's screen centres its title with it, so a
     * loader without it cannot draw a screen the way the mod laid it out. No refusal arm is needed
     * here: this class is loaded only in a client process, where the font always exists.
     */
    @Override
    public double textWidth(String text) {
        return game().textRenderer.getWidth(text);
    }

    /**
     * Mod-owned screens are the screen subclass this loader adds, so a {@code Screen.new()} and
     * {@code Screen.open()} are legal here. The dedicated server answers false, in {@link
     * ServerStationApiAdapter}, and has nothing to show.
     */
    @Override
    public boolean ownedScreensSupported() {
        return true;
    }

    @Override
    public void showOwnedScreen(GrugOwnedScreen screen) {
        // A test ticks on this client's own thread, so the screen is up, with its screen_init
        // asked, by the time this returns.
        game().setScreen(new GrugOwnedScreenHost(screen));
    }

    @Override
    public void hideOwnedScreen(GrugOwnedScreen screen) {
        // The host decides whether it is still the screen the game shows: only the screen this
        // loader made for this mod screen is taken away, because another screen the player opened
        // over it is theirs to keep.
        GrugOwnedScreenHost.hideIfShowing(screen);
    }

    @SuppressWarnings("deprecation")
    private static Minecraft game() {
        return (Minecraft) FabricLoader.getInstance().getGameInstance();
    }
}
