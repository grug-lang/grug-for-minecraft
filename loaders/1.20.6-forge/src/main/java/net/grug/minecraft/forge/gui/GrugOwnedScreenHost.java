package net.grug.minecraft.forge.gui;

import net.grug.minecraft.forge.GrugOverlayHost;
import net.grug.minecraft.grug.GrugListMath;
import net.grug.minecraft.grug.GrugOverlay;
import net.grug.minecraft.grug.GrugOverlayGeometry;
import net.grug.minecraft.grug.GrugOwnedScreen;
import net.grug.minecraft.grug.GrugOwnedScreens;
import net.grug.minecraft.grug.GrugWidget;
import net.grug.minecraft.grug.GrugWidgetKind;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The game's screen for one screen a mod owns: it mirrors the mod's widgets into the game's own and
 * hands every frame and input event back to core, which asks the mod's {@code screen_*} exports.
 *
 * <p>Core owns the lifecycle, the widget model and the ordering: this class adds only what a loader
 * has to add. It builds a vanilla button, checkbox, slider, text field or list for each widget the
 * mod declared, copies what the mod set into them before the frame, and reports what the player did
 * while a widget's own handler runs, so core changes the model before it asks {@code
 * screen_widget_changed} and the export reads the value the action left.
 *
 * <p>The press reaches {@code screen_click} before the widget under the mouse, and {@code take()}
 * keeps it from the widget, as the frozen API says. The wheel reaches {@code screen_scroll} before
 * the list. The keyboard reaches the focused text field and is never asked of {@code screen_key}
 * while a field has it, so typing cannot be taken from the field by the mod.
 *
 * <p>The screen never pauses the game: a mod builds in the world it draws over. A screen the game
 * takes away, on Escape or because another screen replaced it, tells core in {@link #removed()}, on
 * the next client tick, so a {@code screen_closed} that opens another screen runs after the game
 * finished putting the new screen in place.
 */
public final class GrugOwnedScreenHost extends Screen {

    /** The list's own translucent background, as the reference's selector list draws it. */
    private static final int LIST_BACKGROUND = 0x33000000;

    /** The row a list has selected, drawn under its text. */
    private static final int LIST_SELECTION = 0x66FFFFFF;

    /** The line between two rows. */
    private static final int LIST_SEPARATOR = 0x22FFFFFF;

    /** The list's scrollbar track and thumb. */
    private static final int SCROLLBAR_TRACK = 0x30FFFFFF;

    private static final int SCROLLBAR_THUMB = 0xA0FFFFFF;

    /**
     * The colour a row is drawn in when {@code screen_list_row} set none, as the reference draws.
     */
    private static final int ROW_TEXT = 0xFFFFFF;

    /**
     * The longest text a field mirror takes from the mod. The game's own field cuts text at a limit
     * of its own, and the mod's text is the truth, so the limit is far above what a field holds in
     * practice and a value the mod set is never truncated behind its back.
     */
    private static final int TEXT_FIELD_MAX_LENGTH = 1024;

    private final GrugOwnedScreen screen;
    private final Map<GrugWidget, AbstractWidget> mirrors = new LinkedHashMap<>();

    /** Whether the game's widgets are being copied from the model, so their handlers stay quiet. */
    private boolean syncing;

    /** The key whose press is still held, so the next press of it is reported as a repeat. */
    private int heldKey = -1;

    public GrugOwnedScreenHost(GrugOwnedScreen screen) {
        super(Component.literal("Grug screen"));
        this.screen = screen;
    }

    /** The model this screen shows, which is what {@code hideOwnedScreen} compares against. */
    public GrugOwnedScreen model() {
        return screen;
    }

    @Override
    protected void init() {
        // A resize rebuilds the game's widgets from the model: core asks screen_init again first,
        // so
        // the widgets it declared this time are the ones mirrored.
        clearWidgets();
        mirrors.clear();
        GrugOwnedScreens.initScreen(screen, width, height);
        for (GrugWidget widget : screen.widgets()) {
            AbstractWidget mirror = mirrorOf(widget);
            mirrors.put(widget, mirror);
            addRenderableWidget(mirror);
        }
    }

    /** The game's own widget for one the mod declared, at the state the mod last set it to. */
    private AbstractWidget mirrorOf(GrugWidget widget) {
        int x = (int) Math.round(widget.x());
        int y = (int) Math.round(widget.y());
        int widgetWidth = (int) Math.round(widget.width());
        int widgetHeight = (int) Math.round(widget.height());
        if (widget.kind() == GrugWidgetKind.BUTTON) {
            return Button.builder(
                            Component.literal(widget.text()),
                            button -> GrugOwnedScreens.buttonPressed(screen, widget))
                    .bounds(x, y, widgetWidth, widgetHeight)
                    .build();
        }
        if (widget.kind() == GrugWidgetKind.CHECKBOX) {
            return Checkbox.builder(Component.literal(widget.text()), this.font)
                    .pos(x, y)
                    .selected(widget.checked())
                    .onValueChange(
                            (checkbox, checked) -> {
                                if (!syncing) {
                                    GrugOwnedScreens.checkboxChanged(screen, widget, checked);
                                }
                            })
                    .build();
        }
        if (widget.kind() == GrugWidgetKind.SLIDER) {
            return new GrugSlider(screen, widget);
        }
        if (widget.kind() == GrugWidgetKind.TEXT_FIELD) {
            EditBox field =
                    new EditBox(this.font, x, y, widgetWidth, widgetHeight, Component.literal(""));
            field.setMaxLength(TEXT_FIELD_MAX_LENGTH);
            field.setValue(widget.text());
            field.setTextColor(widget.textColour());
            field.setEditable(widget.active());
            field.setResponder(
                    text -> {
                        if (!syncing) {
                            GrugOwnedScreens.textEdited(screen, widget, text);
                        }
                    });
            return field;
        }
        return new GrugListWidget(screen, widget);
    }

    /**
     * Copies what the mod changed outside {@code screen_init} into the game's widgets before the
     * frame: a moved widget, a new label or value, a shown or hidden one. The handlers are told
     * that this is not the player, so a copy never asks {@code screen_widget_changed}.
     */
    private void syncMirrors() {
        syncing = true;
        try {
            for (Map.Entry<GrugWidget, AbstractWidget> entry : mirrors.entrySet()) {
                GrugWidget widget = entry.getKey();
                AbstractWidget mirror = entry.getValue();
                mirror.active = widget.active();
                mirror.visible = widget.visible();
                mirror.setX((int) Math.round(widget.x()));
                mirror.setY((int) Math.round(widget.y()));
                if (widget.kind() == GrugWidgetKind.CHECKBOX) {
                    syncCheckbox(widget, (Checkbox) mirror);
                } else if (widget.kind() == GrugWidgetKind.TEXT_FIELD) {
                    syncTextField(widget, (EditBox) mirror);
                } else if (widget.kind() == GrugWidgetKind.SLIDER) {
                    ((GrugSlider) mirror).mirrorModel();
                } else {
                    mirror.setWidth((int) Math.round(widget.width()));
                    mirror.setHeight((int) Math.round(widget.height()));
                    // A list's own message is its name, not the mod's text: only a button has a
                    // label to copy here.
                    if (widget.kind() == GrugWidgetKind.BUTTON) {
                        syncMessage(widget, mirror);
                    }
                }
            }
        } finally {
            syncing = false;
        }
    }

    /**
     * Copies a checkbox's label, its tick and the size the game gives a checkbox with that label.
     * The game's checkbox has no setter for its tick, so its own toggle is used while the sync is
     * marked, which flips the box and leaves the mod's handler quiet.
     */
    private void syncCheckbox(GrugWidget widget, Checkbox checkbox) {
        String label = widget.text();
        if (!checkbox.getMessage().getString().equals(label)) {
            checkbox.setMessage(Component.literal(label));
            checkbox.setWidth(Checkbox.getBoxSize(this.font) + 4 + this.font.width(label));
        }
        if (checkbox.selected() != widget.checked()) {
            checkbox.onPress();
        }
    }

    private void syncTextField(GrugWidget widget, EditBox field) {
        field.setWidth((int) Math.round(widget.width()));
        field.setHeight((int) Math.round(widget.height()));
        String text = widget.text();
        if (!field.getValue().equals(text)) {
            field.setValue(text);
        }
        field.setTextColor(widget.textColour());
        field.setEditable(widget.active());
    }

    private void syncMessage(GrugWidget widget, AbstractWidget mirror) {
        String text = widget.text();
        if (!mirror.getMessage().getString().equals(text)) {
            mirror.setMessage(Component.literal(text));
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        syncMirrors();
        // The rows are asked before anything is drawn, because the lists draw the answers this
        // frame.
        GrugOwnedScreens.prepareFrame(screen);
        super.render(graphics, mouseX, mouseY, partialTick);
        // screen_render is asked after the background and the widgets, so a mod draws its labels
        // and titles on top of them.
        GrugOverlayGeometry geometry =
                GrugOwnedScreens.renderScreen(screen, width, height, mouseX, mouseY);
        GrugOverlayHost.drawGeometry(graphics, geometry);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // The mod sees the press before the widget under the mouse, and take() keeps it from the
        // widget.
        if (GrugOwnedScreens.clickScreen(
                screen, width, height, mouseX, mouseY, button, Screen.hasShiftDown())) {
            GrugOverlay.noteTestPressTaken();
            return true;
        }
        boolean handled = super.mouseClicked(mouseX, mouseY, button);
        if (!handled) {
            // A click on an empty spot takes the keyboard away from a focused field, as the API
            // says.
            setFocused(null);
        }
        return handled;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        // The mod sees the wheel before the list under it, and take() keeps the list still.
        if (GrugOwnedScreens.scrollScreen(screen, width, height, mouseX, mouseY, scrollY)) {
            GrugOverlay.noteTestPressTaken();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // A focused text field gets the key, and the mod is not asked at all while it has it.
        if (this.getFocused() instanceof EditBox) {
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
        int key = GrugOverlayHost.overlayKey(keyCode);
        if (key >= 0) {
            boolean repeat = keyCode == heldKey;
            heldKey = keyCode;
            boolean ctrl = (modifiers & GrugOverlayHost.controlModifier()) != 0;
            boolean shift = (modifiers & GrugOverlayHost.shiftModifier()) != 0;
            if (GrugOwnedScreens.keyScreen(screen, key, 0.0, ctrl, shift, repeat)) {
                GrugOverlay.noteTestPressTaken();
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
        if (keyCode == heldKey) {
            heldKey = -1;
        }
        return super.keyReleased(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (this.getFocused() instanceof EditBox) {
            return super.charTyped(codePoint, modifiers);
        }
        int character = GrugOverlay.characterCode(codePoint);
        if (character != 0) {
            boolean ctrl = (modifiers & GrugOverlayHost.controlModifier()) != 0;
            boolean alt = (modifiers & GrugOverlayHost.altModifier()) != 0;
            boolean shift = (modifiers & GrugOverlayHost.shiftModifier()) != 0;
            if (GrugOwnedScreens.keyScreen(
                    screen,
                    0.0,
                    character,
                    GrugOverlay.characterEventIsChord(ctrl, alt),
                    shift,
                    false)) {
                GrugOverlay.noteTestPressTaken();
                return true;
            }
        }
        return super.charTyped(codePoint, modifiers);
    }

    @Override
    public void removed() {
        super.removed();
        // The game takes the screen away for Escape, for a screen it put in its place, and for the
        // world being left, and each of those is the same end for the mod. The report waits for the
        // next tick, so a screen_closed that opens another screen does so after the game finished
        // replacing this one rather than inside it.
        Minecraft minecraft = this.minecraft;
        if (minecraft == null) {
            GrugOwnedScreens.gameRemoved(screen);
        } else {
            minecraft.execute(() -> GrugOwnedScreens.gameRemoved(screen));
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /**
     * The game's slider for one the mod declared: it reports the fraction the player left the
     * handle at, and takes back the value the mod kept, which is the clamped one when its export
     * snapped the value.
     */
    private static final class GrugSlider extends AbstractSliderButton {
        private final GrugOwnedScreen screen;
        private final GrugWidget widget;

        GrugSlider(GrugOwnedScreen screen, GrugWidget widget) {
            super(
                    (int) Math.round(widget.x()),
                    (int) Math.round(widget.y()),
                    (int) Math.round(widget.width()),
                    (int) Math.round(widget.height()),
                    Component.literal(widget.sliderLabel()),
                    widget.sliderFraction());
            this.screen = screen;
            this.widget = widget;
        }

        /** Copies the mod's value into the handle, silently. */
        void mirrorModel() {
            this.value = widget.sliderFraction();
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            // The reference's label is the text and the value to tenths, which core formats.
            String label = widget.sliderLabel();
            if (!getMessage().getString().equals(label)) {
                setMessage(Component.literal(label));
            }
        }

        @Override
        protected void applyValue() {
            GrugOwnedScreens.sliderMoved(screen, widget, this.value);
            // The mod's export may have kept a different value, so the handle follows what it kept.
            this.value = widget.sliderFraction();
        }
    }

    /**
     * The list: the game's own selection lists are tied to their own entry classes, so this draws
     * the rectangle, the rows the mod answered, the selection and the scrollbar itself, and reports
     * the row the player clicked.
     */
    private static final class GrugListWidget extends AbstractWidget {
        private final GrugOwnedScreen screen;
        private final GrugWidget widget;

        GrugListWidget(GrugOwnedScreen screen, GrugWidget widget) {
            super(
                    (int) Math.round(widget.x()),
                    (int) Math.round(widget.y()),
                    (int) Math.round(widget.width()),
                    (int) Math.round(widget.height()),
                    Component.literal(widget.name()));
            this.screen = screen;
            this.widget = widget;
        }

        @Override
        protected void renderWidget(
                GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            int left = getX();
            int top = getY();
            int right = left + getWidth();
            int bottom = top + getHeight();
            graphics.fill(left, top, right, bottom, LIST_BACKGROUND);
            Font font = Minecraft.getInstance().font;
            int rowHeight = rowHeight();
            // A partly visible row is drawn, and the scissor keeps it inside the list, as the
            // reference's own list does.
            graphics.enableScissor(left, top, right, bottom);
            for (GrugWidget.Row row : widget.frameRows()) {
                int rowTop =
                        top
                                + (int)
                                        Math.round(
                                                GrugListMath.rowY(
                                                        row.index(),
                                                        widget.scroll(),
                                                        widget.rowHeight()));
                if (row.index() == widget.selected()) {
                    graphics.fill(left, rowTop, right, rowTop + rowHeight, LIST_SELECTION);
                }
                int rgb = row.rgb() < 0 ? ROW_TEXT : row.rgb();
                graphics.drawString(
                        font,
                        row.text(),
                        left + 5,
                        rowTop + (rowHeight - 8) / 2,
                        0xFF000000 | rgb,
                        true);
                graphics.fill(
                        left, rowTop + rowHeight - 1, right, rowTop + rowHeight, LIST_SEPARATOR);
            }
            graphics.disableScissor();
            drawScrollbar(graphics, left, top, right, bottom);
        }

        private int rowHeight() {
            return Math.max(1, (int) Math.round(widget.rowHeight()));
        }

        /** The scrollbar, drawn only while the rows are taller than the list. */
        private void drawScrollbar(GuiGraphics graphics, int left, int top, int right, int bottom) {
            double max = GrugListMath.maxScroll(widget.rows(), widget.rowHeight(), widget.height());
            if (!(max > 0.0)) return;
            int trackHeight = bottom - top;
            double rowsHeight = widget.rows() * widget.rowHeight();
            int thumbHeight =
                    Math.max(8, (int) Math.round(trackHeight * (trackHeight / rowsHeight)));
            thumbHeight = Math.min(thumbHeight, trackHeight);
            int thumbTop =
                    top + (int) Math.round((trackHeight - thumbHeight) * (widget.scroll() / max));
            int barLeft = right - 6;
            graphics.fill(barLeft, top, right, bottom, SCROLLBAR_TRACK);
            graphics.fill(barLeft, thumbTop, right, thumbTop + thumbHeight, SCROLLBAR_THUMB);
        }

        @Override
        public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
            return GrugOwnedScreens.scrollList(screen, widget, scrollY);
        }

        @Override
        public void onClick(double mouseX, double mouseY) {
            int index =
                    GrugListMath.rowAt(
                            mouseY - getY(),
                            getHeight(),
                            widget.scroll(),
                            widget.rows(),
                            widget.rowHeight());
            if (index >= 0) {
                GrugOwnedScreens.listRowClicked(screen, widget, index);
            }
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            output.add(NarratedElementType.TITLE, Component.literal(widget.name()));
        }
    }
}
