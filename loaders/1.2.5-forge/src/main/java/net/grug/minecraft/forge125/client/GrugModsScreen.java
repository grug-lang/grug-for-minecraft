package net.grug.minecraft.forge125.client;

import net.grug.minecraft.grug.GrugModsScreenModel;
import net.minecraft.src.GuiButton;
import net.minecraft.src.GuiScreen;

import java.util.List;

/** The mod manager's screen (#335): one button per grug mod, and a popup that blocks the rest. */
public class GrugModsScreen extends GuiScreen {
    private static final int FIRST_MOD = 0;
    private static final int PREVIOUS = 10;
    private static final int NEXT = 11;
    private static final int DONE = 12;
    private static final int ACCEPT = 13;
    private static final int DECLINE = 14;

    private final GuiScreen parent;
    private final GrugModsScreenModel model;

    public GrugModsScreen(GuiScreen parent, GrugModsScreenModel model) {
        this.parent = parent;
        this.model = model;
    }

    @Override
    public void initGui() {
        controlList.clear();
        int x = width / 2 - 100;
        boolean blocked = model.popup() != null;
        List<String> labels = model.labels();
        for (int i = 0; i < labels.size(); i++) {
            controlList.add(new GuiButton(FIRST_MOD + i, x, 36 + 22 * i, 200, 20, labels.get(i)));
        }
        controlList.add(new GuiButton(PREVIOUS, x, 148, 20, 20, "<"));
        controlList.add(new GuiButton(NEXT, x + 180, 148, 20, 20, ">"));
        controlList.add(new GuiButton(DONE, x, 186, 200, 20, "Done"));
        if (blocked) {
            controlList.add(new GuiButton(ACCEPT, x, 112, 98, 20, "Accept"));
            controlList.add(new GuiButton(DECLINE, x + 102, 112, 98, 20, "Decline"));
        }
        for (Object control : controlList) {
            GuiButton button = (GuiButton) control;
            boolean popupButton = button.id == ACCEPT || button.id == DECLINE;
            button.enabled = popupButton || (!blocked && enabled(button.id));
        }
    }

    private boolean enabled(int id) {
        if (id == PREVIOUS) return model.hasPrevious();
        if (id == NEXT) return model.hasNext();
        return true;
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == DONE) {
            mc.displayGuiScreen(parent);
            return;
        }
        if (button.id == PREVIOUS) model.previous();
        else if (button.id == NEXT) model.next();
        else if (button.id == ACCEPT) model.accept();
        else if (button.id == DECLINE) model.decline();
        else model.click(button.id - FIRST_MOD);
        initGui();
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        drawCenteredString(fontRenderer, "Grug Mods", width / 2, 16, 0xFFFFFF);
        if (model.restartNeeded()) {
            drawCenteredString(fontRenderer, "Restart the game to apply", width / 2, 174, 0xFFFF55);
        }
        super.drawScreen(mouseX, mouseY, partialTicks);
        String popup = model.popup();
        if (popup != null) {
            drawRect(width / 2 - 110, 56, width / 2 + 110, 140, 0xEE000000);
            int y = 62;
            for (String line : GrugModsScreenModel.wrap(popup, 200, fontRenderer::getStringWidth)) {
                drawString(fontRenderer, line, width / 2 - 100, y, 0xFFFFFF);
                y += 10;
            }
            // The popup's own buttons, drawn again over its backdrop.
            for (Object control : controlList) {
                GuiButton button = (GuiButton) control;
                if (button.id == ACCEPT || button.id == DECLINE) {
                    button.drawButton(mc, mouseX, mouseY);
                }
            }
        }
    }
}
