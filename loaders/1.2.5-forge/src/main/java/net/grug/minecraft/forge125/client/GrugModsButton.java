package net.grug.minecraft.forge125.client;

import net.grug.minecraft.grug.GrugModManager;
import net.grug.minecraft.grug.GrugModsScreenModel;
import net.minecraft.client.Minecraft;
import net.minecraft.src.GuiButton;
import net.minecraft.src.GuiScreen;

/**
 * The title screen's button that opens the mod manager's screen (#335).
 *
 * <p>The main menu only reacts to the ids it knows, so this button opens the screen itself when it
 * is pressed, and reports the press as unhandled so the menu does nothing else with it.
 */
public class GrugModsButton extends GuiButton {
    private final GuiScreen menu;

    public GrugModsButton(GuiScreen menu) {
        super(0x6772, 6, 6, 80, 20, "Grug Mods");
        this.menu = menu;
    }

    @Override
    public boolean mousePressed(Minecraft minecraft, int x, int y) {
        if (!super.mousePressed(minecraft, x, y)) {
            return false;
        }
        minecraft.sndManager.playSoundFX("random.click", 1.0F, 1.0F);
        GrugModManager manager = GrugModManager.of(minecraft.mcDataDir);
        minecraft.displayGuiScreen(new GrugModsScreen(menu, new GrugModsScreenModel(manager, 5)));
        return false;
    }
}
