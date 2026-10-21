package net.grug.minecraft.stationapi.gui;

import net.grug.minecraft.grug.GrugModsScreenModel;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;

import java.util.List;

/** The mod manager's screen (#335): one button per grug mod, and a popup that blocks the rest. */
public class GrugModsScreen extends Screen {
    private static final int FIRST_MOD = 0;
    private static final int PREVIOUS = 10;
    private static final int NEXT = 11;
    private static final int DONE = 12;
    private static final int ACCEPT = 13;
    private static final int DECLINE = 14;
    private static final int UPDATE_ALL = 15;
    private static final int FIRST_UPDATE = 20;

    private final Screen parent;
    private final GrugModsScreenModel model;

    public GrugModsScreen(Screen parent, GrugModsScreenModel model) {
        this.parent = parent;
        this.model = model;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void init() {
        buttons.clear();
        int x = width / 2 - 100;
        boolean blocked = model.popup() != null;
        List<String> labels = model.labels();
        List<Boolean> updates = model.updates();
        for (int i = 0; i < labels.size(); i++) {
            int modWidth = updates.get(i) ? 150 : 200;
            buttons.add(new Button(FIRST_MOD + i, x, 36 + 22 * i, modWidth, labels.get(i)));
            if (updates.get(i)) {
                buttons.add(new Button(FIRST_UPDATE + i, x + 154, 36 + 22 * i, 46, "Update"));
            }
        }
        String updateAll = model.updateAllLabel();
        if (updateAll != null) {
            buttons.add(new Button(UPDATE_ALL, x + 24, 148, 152, updateAll));
        }
        buttons.add(new Button(PREVIOUS, x, 148, 20, "<"));
        buttons.add(new Button(NEXT, x + 180, 148, 20, ">"));
        buttons.add(new Button(DONE, x, 186, 200, "Done"));
        if (blocked) {
            buttons.add(new Button(ACCEPT, x, 112, 98, "Accept"));
            buttons.add(new Button(DECLINE, x + 102, 112, 98, "Decline"));
        }
        for (Object control : buttons) {
            ButtonWidget button = (ButtonWidget) control;
            boolean popupButton = button.id == ACCEPT || button.id == DECLINE;
            button.active = popupButton || (!blocked && enabled(button.id));
        }
    }

    private boolean enabled(int id) {
        if (id == DONE) return true;
        if (model.busy()) return false;
        if (id == PREVIOUS) return model.hasPrevious();
        if (id == NEXT) return model.hasNext();
        return true;
    }

    /** A 20 pixel high button of a given width, which the buttons' constructors differ on. */
    private static final class Button extends ButtonWidget {
        Button(int id, int x, int y, int width, String message) {
            super(id, x, y, width, 20, message);
        }
    }

    @Override
    protected void buttonClicked(ButtonWidget button) {
        if (button.id == DONE) {
            minecraft.setScreen(parent);
            return;
        }
        if (button.id == PREVIOUS) model.previous();
        else if (button.id == NEXT) model.next();
        else if (button.id == ACCEPT) model.accept();
        else if (button.id == DECLINE) model.decline();
        else if (button.id == UPDATE_ALL) model.clickUpdateAll();
        else if (button.id >= FIRST_UPDATE) model.clickUpdate(button.id - FIRST_UPDATE);
        else model.click(button.id - FIRST_MOD);
        init();
    }

    @Override
    public void render(int mouseX, int mouseY, float tickDelta) {
        if (model.poll()) {
            init();
        }
        renderBackground();
        drawCenteredTextWithShadow(textRenderer, "Grug Mods", width / 2, 16, 0xFFFFFF);
        if (model.restartNeeded()) {
            drawCenteredTextWithShadow(
                    textRenderer, "Restart the game to apply", width / 2, 174, 0xFFFF55);
        }
        String status = model.status();
        if (status != null) {
            drawCenteredTextWithShadow(textRenderer, status, width / 2, 210, 0xFFFFFF);
            double progress = model.progress();
            if (progress >= 0) {
                int x = width / 2 - 100;
                fill(x - 1, 221, x + 201, 229, 0xFF000000);
                fill(x, 222, x + 200, 228, 0xFF555555);
                fill(x, 222, x + (int) (200 * progress), 228, 0xFF55FF55);
            }
        } else {
            int errorY = 210;
            for (String line : model.errorLines(400, textRenderer::getWidth)) {
                drawCenteredTextWithShadow(textRenderer, line, width / 2, errorY, 0xFF5555);
                errorY += 10;
            }
        }
        super.render(mouseX, mouseY, tickDelta);
        String popup = model.popup();
        if (popup != null) {
            fill(width / 2 - 110, 56, width / 2 + 110, 140, 0xEE000000);
            int y = 62;
            for (String line : GrugModsScreenModel.wrap(popup, 200, textRenderer::getWidth)) {
                drawTextWithShadow(textRenderer, line, width / 2 - 100, y, 0xFFFFFF);
                y += 10;
            }
            // The popup's own buttons, drawn again over its backdrop.
            for (Object control : buttons) {
                ButtonWidget button = (ButtonWidget) control;
                if (button.id == ACCEPT || button.id == DECLINE) {
                    button.render(minecraft, mouseX, mouseY);
                }
            }
        }
    }
}
