package net.grug.minecraft.forge.gui;

import net.grug.minecraft.grug.GrugModsScreenModel;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** The mod manager's screen (#335): one button per grug mod, and a popup that blocks the rest. */
public class GrugModsScreen extends Screen {
    private final Screen parent;
    private final GrugModsScreenModel model;
    private final List<Button> popupButtons = new ArrayList<>();

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
        List<Boolean> updates = model.updates();
        for (int i = 0; i < labels.size(); i++) {
            int row = i;
            Button button =
                    Button.builder(Component.literal(labels.get(i)), b -> clicked(row))
                            .bounds(x, 36 + 22 * i, updates.get(i) ? 150 : 200, 20)
                            .build();
            button.active = !blocked && !model.busy();
            addRenderableWidget(button);
            if (updates.get(i)) {
                Button update =
                        Button.builder(Component.literal("Update"), b -> updated(row))
                                .bounds(x + 154, 36 + 22 * i, 46, 20)
                                .build();
                update.active = !blocked && !model.busy();
                addRenderableWidget(update);
            }
        }
        String updateAll = model.updateAllLabel();
        if (updateAll != null) {
            Button all =
                    Button.builder(Component.literal(updateAll), b -> updatedAll())
                            .bounds(x + 24, 148, 152, 20)
                            .build();
            all.active = !blocked && !model.busy();
            addRenderableWidget(all);
        }
        Button previous =
                Button.builder(Component.literal("<"), b -> paged(false))
                        .bounds(x, 148, 20, 20)
                        .build();
        previous.active = !blocked && !model.busy() && model.hasPrevious();
        addRenderableWidget(previous);
        Button next =
                Button.builder(Component.literal(">"), b -> paged(true))
                        .bounds(x + 180, 148, 20, 20)
                        .build();
        next.active = !blocked && !model.busy() && model.hasNext();
        addRenderableWidget(next);
        Button done =
                Button.builder(Component.literal("Done"), b -> onClose())
                        .bounds(x, 186, 200, 20)
                        .build();
        done.active = !blocked;
        addRenderableWidget(done);
        popupButtons.clear();
        if (blocked) {
            popupButtons.add(
                    Button.builder(
                                    Component.literal("Accept"),
                                    b -> {
                                        model.accept();
                                        rebuildWidgets();
                                    })
                            .bounds(x, 112, 98, 20)
                            .build());
            popupButtons.add(
                    Button.builder(
                                    Component.literal("Decline"),
                                    b -> {
                                        model.decline();
                                        rebuildWidgets();
                                    })
                            .bounds(x + 102, 112, 98, 20)
                            .build());
            popupButtons.forEach(this::addRenderableWidget);
        }
    }

    private void clicked(int row) {
        model.click(row);
        rebuildWidgets();
    }

    private void updated(int row) {
        model.clickUpdate(row);
        rebuildWidgets();
    }

    private void updatedAll() {
        model.clickUpdateAll();
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
        if (model.poll()) {
            rebuildWidgets();
        }
        // Screen.render draws the background, so the texts come after it.
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, 16, 0xFFFFFF);
        if (model.restartNeeded()) {
            graphics.drawCenteredString(
                    font, "Restart the game to apply", width / 2, 174, 0xFFFF55);
        }
        String status = model.status();
        if (status != null) {
            graphics.drawCenteredString(font, status, width / 2, 210, 0xFFFFFF);
            double progress = model.progress();
            if (progress >= 0) {
                int x = width / 2 - 100;
                graphics.fill(x - 1, 221, x + 201, 229, 0xFF000000);
                graphics.fill(x, 222, x + 200, 228, 0xFF555555);
                graphics.fill(x, 222, x + (int) (200 * progress), 228, 0xFF55FF55);
            }
        } else {
            int errorY = 210;
            for (String line : model.errorLines(400, font::width)) {
                graphics.drawCenteredString(font, line, width / 2, errorY, 0xFF5555);
                errorY += 10;
            }
        }
        String popup = model.popup();
        if (popup != null) {
            // Over the widgets behind it, so only the popup's own buttons, drawn again over the
            // backdrop, are readable.
            graphics.pose().pushPose();
            graphics.pose().translate(0, 0, 400);
            graphics.fill(width / 2 - 110, 56, width / 2 + 110, 140, 0xEE000000);
            graphics.drawWordWrap(
                    font, Component.literal(popup), width / 2 - 100, 62, 200, 0xFFFFFF);
            for (Button button : popupButtons) {
                button.render(graphics, mouseX, mouseY, partialTick);
            }
            graphics.pose().popPose();
        }
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }
}
