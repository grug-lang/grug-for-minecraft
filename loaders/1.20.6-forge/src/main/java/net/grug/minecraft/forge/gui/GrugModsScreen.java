package net.grug.minecraft.forge.gui;

import net.grug.minecraft.grug.GrugModsScreenModel;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/** The mod manager's screen (#335): one button per grug mod, and a popup that blocks the rest. */
public class GrugModsScreen extends Screen {
    private final Screen parent;
    private final GrugModsScreenModel model;

    public GrugModsScreen(Screen parent, GrugModsScreenModel model) {
        super(Component.literal("Grug Mods"));
        this.parent = parent;
        this.model = model;
    }

    @Override
    protected void init() {
        int x = width / 2 - 100;
        boolean blocked = model.popup() != null;
        List<String> labels = model.labels();
        for (int i = 0; i < labels.size(); i++) {
            int row = i;
            Button button =
                    Button.builder(Component.literal(labels.get(i)), b -> clicked(row))
                            .bounds(x, 36 + 22 * i, 200, 20)
                            .build();
            button.active = !blocked;
            addRenderableWidget(button);
        }
        Button previous =
                Button.builder(Component.literal("<"), b -> paged(false))
                        .bounds(x, 148, 20, 20)
                        .build();
        previous.active = !blocked && model.hasPrevious();
        addRenderableWidget(previous);
        Button next =
                Button.builder(Component.literal(">"), b -> paged(true))
                        .bounds(x + 180, 148, 20, 20)
                        .build();
        next.active = !blocked && model.hasNext();
        addRenderableWidget(next);
        Button done =
                Button.builder(Component.literal("Done"), b -> onClose())
                        .bounds(x, 186, 200, 20)
                        .build();
        done.active = !blocked;
        addRenderableWidget(done);
        if (blocked) {
            addRenderableWidget(
                    Button.builder(
                                    Component.literal("Accept"),
                                    b -> {
                                        model.accept();
                                        rebuildWidgets();
                                    })
                            .bounds(x, 112, 98, 20)
                            .build());
            addRenderableWidget(
                    Button.builder(
                                    Component.literal("Decline"),
                                    b -> {
                                        model.decline();
                                        rebuildWidgets();
                                    })
                            .bounds(x + 102, 112, 98, 20)
                            .build());
        }
    }

    private void clicked(int row) {
        model.click(row);
        rebuildWidgets();
    }

    private void paged(boolean forward) {
        if (forward) {
            model.next();
        } else {
            model.previous();
        }
        rebuildWidgets();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, 16, 0xFFFFFF);
        if (model.restartNeeded()) {
            graphics.drawCenteredString(
                    font, "Restart the game to apply", width / 2, 174, 0xFFFF55);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
        String popup = model.popup();
        if (popup != null) {
            // Over the widgets behind it, so only the popup's own buttons are readable.
            graphics.pose().translate(0, 0, 400);
            graphics.fill(width / 2 - 110, 56, width / 2 + 110, 140, 0xEE000000);
            graphics.drawWordWrap(
                    font, Component.literal(popup), width / 2 - 100, 62, 200, 0xFFFFFF);
        }
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }
}
