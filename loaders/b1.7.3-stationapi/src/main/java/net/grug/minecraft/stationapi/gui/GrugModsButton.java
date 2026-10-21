package net.grug.minecraft.stationapi.gui;

import net.fabricmc.loader.api.FabricLoader;
import net.grug.minecraft.grug.GrugModManager;
import net.grug.minecraft.grug.GrugModsScreenModel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;

/**
 * The title screen's button that opens the mod manager's screen (#335).
 *
 * <p>The title screen only reacts to the ids it knows, so this button opens the screen itself when
 * it is pressed, and reports the press as unhandled so the title screen does nothing else with it.
 */
public class GrugModsButton extends ButtonWidget {
    private final Screen title;

    public GrugModsButton(Screen title) {
        super(0x6772, 6, title.height - 26, 80, 20, "Grug Mods");
        this.title = title;
    }

    @Override
    public boolean isMouseOver(Minecraft minecraft, int x, int y) {
        if (!super.isMouseOver(minecraft, x, y)) {
            return false;
        }
        GrugModManager manager =
                GrugModManager.of(FabricLoader.getInstance().getGameDir().toFile());
        minecraft.setScreen(new GrugModsScreen(title, new GrugModsScreenModel(manager, 5)));
        return false;
    }
}
