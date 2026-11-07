package net.grug.minecraft.ornithe.client;

import net.fabricmc.api.ClientModInitializer;
import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.gui.GrugGuiBuilder;
import net.grug.minecraft.ornithe.gui.GrugScreenPacket;

/**
 * Installs the client's half of {@link GrugScreenPacket}, which is how a server opens a grug GUI
 * for this player (#1098).
 *
 * <p>It is a Beta-only entrypoint because the handler names {@link GrugScreen}, which only this
 * loader has: {@code ClientGrugModLoader} is shared with Alpha, whose screen is a different class.
 */
@GrugGenerated("client glue: the screen it installs is excluded from coverage")
public class ClientGrugScreens implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        GrugScreenPacket.clientHandler =
                new GrugScreenPacket.Handler() {
                    @Override
                    public void openScreen(
                            int x, int y, int z, int windowId, GrugGuiBuilder layout) {
                        GrugScreen.openFromServer(x, y, z, windowId, layout);
                    }
                };
    }
}
