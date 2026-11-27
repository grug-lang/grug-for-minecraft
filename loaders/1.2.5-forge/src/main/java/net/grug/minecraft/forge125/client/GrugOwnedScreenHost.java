package net.grug.minecraft.forge125.client;

import net.grug.minecraft.core.GrugRunWindow;
import net.grug.minecraft.forge125.GrugTextFieldAccess;
import net.grug.minecraft.grug.GrugListMath;
import net.grug.minecraft.grug.GrugOverlay;
import net.grug.minecraft.grug.GrugOverlayGeometry;
import net.grug.minecraft.grug.GrugOwnedScreen;
import net.grug.minecraft.grug.GrugOwnedScreens;
import net.grug.minecraft.grug.GrugWidget;
import net.grug.minecraft.grug.GrugWidgetKind;
import net.minecraft.client.Minecraft;
import net.minecraft.src.Gui;
import net.minecraft.src.GuiButton;
import net.minecraft.src.GuiScreen;
import net.minecraft.src.GuiTextField;
import net.minecraft.src.ScaledResolution;

import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The game's screen for one screen a mod owns: it mirrors the mod's widgets into 1.2.5's own and
 * hands every frame and input event back to core, which asks the mod's {@code screen_*} exports.
 *
 * <p>Core owns the lifecycle, the widget model and the ordering: this class adds only what this
 * version has to add. A button is a vanilla {@link GuiButton}, and a slider subclasses it with the
 * same track and handle drawing but no game option behind it. This version has no checkbox, no
 * placeable list and no screen wheel, so a checkbox and a list are hand-drawn from the model and
 * the wheel is taken in the patched input hook. A text field is the game's own {@link
 * GuiTextField}, which is the best of the four loaders: it has a caret, a selection and the
 * clipboard.
 *
 * <p>The press reaches {@code screen_click} before the widget under the mouse, and {@code take()}
 * keeps it from the widget, as the frozen API says. The wheel reaches {@code screen_scroll} before
 * the list. The keyboard reaches the focused text field and is never asked of {@code screen_key}
 * while a field has it, so typing cannot be taken from the field by the mod, except for Escape: the
 * game closes the screen with it, so a mod that runs its screen's own close from screen_key would
 * otherwise never hear of the close.
 *
 * <p>The screen never pauses the game: a mod builds in the world it draws over. A screen the game
 * takes away, on Escape or because another screen replaced it, tells core in {@link #removed()}, on
 * the next client tick, so a {@code screen_closed} that opens another screen runs after the game
 * finished putting the new screen in place.
 */
public final class GrugOwnedScreenHost extends GuiScreen {

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

    /** The light border and dark interior of the hand-drawn checkbox. */
    private static final int CHECKBOX_BORDER = 0xFFA0A0A0;

    private static final int CHECKBOX_INTERIOR = 0xFF000000;

    /** The tick inside a checked box. */
    private static final int CHECKBOX_MARK = 0xFFFFFFFF;

    /**
     * The side of a checkbox's own box. The box keeps this size whatever width and height the mod
     * declared, as {@code mod_api.json} promises for the game's own checkbox, which is 9 + 8 on
     * 1.20.6; the label sits four pixels to its right.
     */
    private static final int CHECKBOX_BOX = 17;

    /** The label colour of a control the game draws as a plain label. */
    private static final int LABEL = 14737632;

    /** The label colour of a control that is not active. */
    private static final int LABEL_DISABLED = -6250336;

    /**
     * The longest text a field mirror takes from the mod. The game's own field cuts text at a limit
     * of its own, and the mod's text is the truth, so the limit is far above what a field holds in
     * practice and a value the mod set is never truncated behind its back.
     */
    private static final int TEXT_FIELD_MAX_LENGTH = 1024;

    private final GrugOwnedScreen screen;

    /** The vanilla button for each button or slider widget, in declaration order. */
    private final Map<GrugWidget, GuiButton> buttons = new LinkedHashMap<>();

    /** Which widget each button in the control list belongs to. */
    private final Map<GuiButton, GrugWidget> buttonWidgets = new LinkedHashMap<>();

    /** The game's field for each text field widget. */
    private final Map<GrugWidget, GuiTextField> fields = new LinkedHashMap<>();

    /**
     * The bounds each field was made with, because the game's field keeps its own as final and a
     * mod's late {@code set_position} is served by making the field again.
     */
    private final Map<GrugWidget, int[]> fieldBounds = new LinkedHashMap<>();

    /** The next id for a button in the control list, which only has to be unique here. */
    private int nextButtonId = 0;

    /**
     * The screen's open time when this host was made. The game removes a host it replaces, which
     * happens when the mod's own `screen_closed` reopened the screen, and that removal must not end
     * the open time that came after it (#1871's review).
     */
    private final long generation;

    public GrugOwnedScreenHost(GrugOwnedScreen screen) {
        this.screen = screen;
        this.generation = screen.generation();
    }

    /** The model this screen shows, which is what {@code hideOwnedScreen} compares against. */
    public GrugOwnedScreen model() {
        return screen;
    }

    @Override
    public void initGui() {
        // A resize rebuilds the game's widgets from the model: core asks screen_init again first,
        // so the widgets it declared this time are the ones mirrored.
        controlList.clear();
        buttons.clear();
        buttonWidgets.clear();
        fields.clear();
        fieldBounds.clear();
        GrugOwnedScreens.initScreen(screen, width, height);
        for (GrugWidget widget : screen.widgets()) {
            if (widget.kind() == GrugWidgetKind.BUTTON) {
                GuiButton button =
                        new GuiButton(
                                nextButtonId++,
                                round(widget.x()),
                                round(widget.y()),
                                Math.max(1, round(widget.width())),
                                Math.max(1, round(widget.height())),
                                widget.text());
                controlList.add(button);
                buttons.put(widget, button);
                buttonWidgets.put(button, widget);
            } else if (widget.kind() == GrugWidgetKind.SLIDER) {
                GrugSliderButton slider = new GrugSliderButton(nextButtonId++, screen, widget);
                controlList.add(slider);
                buttons.put(widget, slider);
                buttonWidgets.put(slider, widget);
            } else if (widget.kind() == GrugWidgetKind.TEXT_FIELD) {
                fields.put(widget, newField(widget));
                fieldBounds.put(widget, bounds(widget));
            }
        }
    }

    /** The game's field for one the mod declared, at the state the mod last set it to. */
    private GuiTextField newField(GrugWidget widget) {
        GuiTextField field =
                new GuiTextField(
                        fontRenderer,
                        round(widget.x()),
                        round(widget.y()),
                        Math.max(1, round(widget.width())),
                        Math.max(1, round(widget.height())));
        field.setMaxStringLength(TEXT_FIELD_MAX_LENGTH);
        field.setText(widget.text());
        field.setFocused(false);
        GrugTextFieldAccess.setEnabled(field, widget.active());
        GrugTextFieldAccess.setTextColour(field, widget.textColour());
        return field;
    }

    /**
     * Copies what the mod changed outside {@code screen_init} into the game's widgets before the
     * frame: a moved widget, a new label or value, a shown or hidden one. The handlers are told
     * that this is not the player, so a copy never asks {@code screen_widget_changed}.
     */
    private void syncWidgets() {
        for (GrugWidget widget : screen.widgets()) {
            if (widget.kind() == GrugWidgetKind.TEXT_FIELD) {
                syncField(widget);
                continue;
            }
            GuiButton button = buttons.get(widget);
            if (button != null) {
                button.enabled = widget.active();
                button.drawButton = widget.visible();
                button.xPosition = round(widget.x());
                button.yPosition = round(widget.y());
                button.displayString =
                        widget.kind() == GrugWidgetKind.SLIDER
                                ? widget.sliderLabel()
                                : widget.text();
            }
        }
    }

    private void syncField(GrugWidget widget) {
        GuiTextField field = fields.get(widget);
        int[] bounds = bounds(widget);
        if (field == null || !sameBounds(fieldBounds.get(widget), bounds)) {
            // The game's field keeps its position and size final, so a mod that moved or resized it
            // after it was declared gets a field made at the new geometry, keeping its text and,
            // where the old one had it, the focus.
            boolean wasFocused = field != null && field.getIsFocused();
            field = newField(widget);
            field.setFocused(wasFocused);
            fields.put(widget, field);
            fieldBounds.put(widget, bounds);
        }
        if (!field.getText().equals(widget.text())) {
            field.setText(widget.text());
        }
        GrugTextFieldAccess.setEnabled(field, widget.active());
        if (!widget.active()) {
            // A field the mod made inactive gives the keyboard up, as it cannot take a character.
            field.setFocused(false);
        }
        GrugTextFieldAccess.setTextColour(field, widget.textColour());
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        // A field's caret blinks on this counter, which the game's own screens advance once a tick.
        for (GuiTextField field : fields.values()) {
            field.updateCursorCounter();
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        // The first-frame record compares the drawn mark with the hook that cleared it before this
        // frame, so the pass is noted before anything is drawn.
        GrugOverlay.noteFrame(this);
        // A test that parked the cursor for a capture is where the frame must see the mouse: this
        // version reads its pointer from LWJGL's own position, which a warp does not always move,
        // so the parked point wins when a test set one. The click handlers below keep the event's
        // own position, which is where the player really pressed.
        double[] parked =
                GrugRunWindow.parkedMousePosition(mc.displayWidth, mc.displayHeight, width, height);
        if (parked != null) {
            mouseX = (int) parked[0];
            mouseY = (int) parked[1];
        }
        syncWidgets();
        // The rows are asked before anything is drawn, because the lists draw the answers this
        // frame.
        GrugOwnedScreens.prepareFrame(screen);
        // A row export may have moved, shown or hidden a widget while it answered, and the game's
        // copies are a frame behind: syncing again here draws and hit-tests the widgets the mod
        // laid out for this frame (#1871's review).
        syncWidgets();
        drawDefaultBackground();
        for (GrugWidget widget : screen.widgets()) {
            if (!widget.visible()) continue;
            if (widget.kind() == GrugWidgetKind.BUTTON || widget.kind() == GrugWidgetKind.SLIDER) {
                GuiButton button = buttons.get(widget);
                if (button != null) button.drawButton(mc, mouseX, mouseY);
            } else if (widget.kind() == GrugWidgetKind.CHECKBOX) {
                drawCheckbox(widget);
            } else if (widget.kind() == GrugWidgetKind.TEXT_FIELD) {
                GuiTextField field = fields.get(widget);
                if (field != null) field.drawTextBox();
            } else if (widget.kind() == GrugWidgetKind.LIST) {
                drawList(widget);
            }
        }
        // screen_render is asked after the background and the widgets, so a mod draws its labels
        // and titles on top of them.
        GrugOverlayGeometry geometry =
                GrugOwnedScreens.renderScreen(screen, width, height, mouseX, mouseY);
        GrugOverlayHost.drawGeometry(mc, geometry);
        GrugOwnedScreens.noteScreenFrame(screen);
    }

    @Override
    protected void mouseClicked(int x, int y, int button) {
        // The mod sees the press before the widget under the mouse, and take() keeps it from the
        // widget.
        if (GrugOwnedScreens.clickScreen(
                screen, width, height, x, y, button, GuiScreen.isShiftKeyDown())) {
            GrugOverlay.noteTestPressTaken();
            return;
        }
        // The mod may have shown, hidden or moved a widget during that call, and the game's copies
        // are a frame behind. A press that lands on a button or a slider is routed by the copies'
        // own visibility, bounds and position, so they are brought up to date first: otherwise a
        // widget the mod just hid still takes the press meant for the widget it revealed. A player
        // pressing twice within one frame reaches this, and so does a test pressing on two ticks,
        // because the runner runs many ticks per frame.
        syncWidgets();
        for (GrugWidget widget : screen.widgets()) {
            if (!widget.visible() || !widget.active()) continue;
            if (!pressReaches(widget, x, y)) continue;
            if (widget.kind() == GrugWidgetKind.TEXT_FIELD) {
                GuiTextField field = fields.get(widget);
                if (field != null) {
                    // One field has the keyboard at a time, so the others give it up first.
                    releaseFields(field);
                    field.mouseClicked(x, y, button);
                    return;
                }
            } else if (widget.kind() == GrugWidgetKind.CHECKBOX) {
                releaseFields(null);
                if (button == 0) {
                    GrugOwnedScreens.checkboxChanged(screen, widget, !widget.checked());
                }
                return;
            } else if (widget.kind() == GrugWidgetKind.LIST) {
                releaseFields(null);
                if (button == 0) {
                    int index =
                            GrugListMath.rowAt(
                                    y - widget.y(),
                                    widget.height(),
                                    widget.scroll(),
                                    widget.rows(),
                                    widget.rowHeight());
                    if (index >= 0) {
                        GrugOwnedScreens.listRowClicked(screen, widget, index);
                    }
                }
                return;
            }
            // A button or a slider: the game's own press path in the control list takes it.
            break;
        }
        super.mouseClicked(x, y, button);
        // A click on an empty spot takes the keyboard away from a focused field, as the API says,
        // and so does a click on a control that is not a field.
        releaseFields(null);
    }

    /** Takes the keyboard from every field but {@code keep}, which may be null. */
    private void releaseFields(GuiTextField keep) {
        for (GuiTextField field : fields.values()) {
            if (field != keep) field.setFocused(false);
        }
    }

    @Override
    protected void keyTyped(char character, int key) {
        pressKey(
                character,
                key,
                GuiScreen.isCtrlKeyDown(),
                GuiScreen.isShiftKeyDown(),
                Keyboard.isRepeatEvent());
    }

    /**
     * Delivers one press: a focused field gets it, otherwise {@code screen_key} is asked and a
     * press it does not take closes the screen when it is Escape. A real press and a test's
     * synthetic one both land here, so the two see the same order. Answers whether the press was
     * used up, which is always true because Escape is the screen's own close.
     */
    boolean pressKey(char character, int key, boolean ctrl, boolean shift, boolean repeat) {
        GuiTextField focused = focusedField();
        if (focused != null && key != Keyboard.KEY_ESCAPE) {
            // The API says screen_key is not asked while a field has the keyboard, so typing
            // cannot be taken from the field by the mod. Escape is the exception: the game closes
            // the screen with it, so asking screen_key first lets a mod whose close saves state
            // hear of the close instead of losing what it would have written.
            focused.textboxKeyTyped(character, key);
            reportFieldText(focused);
            return true;
        }
        if (GrugOverlayHost.pressScreenKey(this, character, key, ctrl, shift, repeat)) {
            return true;
        }
        if (key == Keyboard.KEY_ESCAPE) {
            closeScreen();
        }
        return true;
    }

    /**
     * Asks the mod's {@code screen_scroll} for one wheel movement and then the list under the
     * point, which is the order the API gives. Answers whether the export took the movement.
     */
    boolean scroll(int x, int y, double delta) {
        if (GrugOwnedScreens.scrollScreen(screen, width, height, x, y, delta)) {
            GrugOverlay.noteTestPressTaken();
            return true;
        }
        GrugWidget list = GrugOwnedScreens.listAt(screen, x, y);
        if (list != null) {
            // A list that is not active does not move, but the wheel over it is still absorbed
            // there and does not reach the hotbar or a list behind it.
            GrugOwnedScreens.scrollList(screen, list, delta);
            return true;
        }
        return false;
    }

    /** The field that has the keyboard, or null when none does. */
    private GuiTextField focusedField() {
        for (GuiTextField field : fields.values()) {
            if (field.getIsFocused()) return field;
        }
        return null;
    }

    /** Tells core about an edit a field made, and only when the text really changed. */
    private void reportFieldText(GuiTextField field) {
        for (Map.Entry<GrugWidget, GuiTextField> entry : fields.entrySet()) {
            if (entry.getValue() != field) continue;
            GrugWidget widget = entry.getKey();
            String text = field.getText();
            if (!text.equals(widget.text())) {
                GrugOwnedScreens.textEdited(screen, widget, text);
            }
            return;
        }
    }

    /** Closes this screen the way {@code close_screen} does on this loader. */
    private void closeScreen() {
        if (mc.currentScreen == this) {
            mc.displayGuiScreen(new GrugEmptyScreen());
        }
    }

    @Override
    public void onGuiClosed() {
        super.onGuiClosed();
        // The game takes the screen away for Escape, for a screen it put in its place, and for the
        // world being left, and each of those is the same end for the mod. A screen the mod closed
        // itself is already ended, so only one that is still open is noted, and the end waits for
        // the next client tick: the game's own call runs inside `displayGuiScreen` before it
        // assigns the replacement, so a screen_closed that opens another screen would have it
        // dropped.
        if (GrugOwnedScreens.openScreen() == screen && screen.generation() == generation) {
            GrugOwnedScreens.noteGameRemoved(screen, generation);
        }
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        // A slider reports its drag itself, and this version's press path calls this after it.
        if (button instanceof GrugSliderButton) return;
        GrugWidget widget = buttonWidgets.get(button);
        if (widget != null) {
            GrugOwnedScreens.buttonPressed(screen, widget);
        }
    }

    @Override
    public boolean doesGuiPauseGame() {
        // The world must keep ticking: a mod screen is drawn over a world someone builds in.
        return false;
    }

    /**
     * Whether a press at this point is on the widget the frame drew. A checkbox is a square of the
     * box's own size with its label beside it, whatever rectangle the mod declared, so the empty
     * space past a wide declaration is not part of it, as it is not on the game's own checkbox.
     * Every other widget draws the rectangle it was given.
     */
    private boolean pressReaches(GrugWidget widget, int x, int y) {
        if (widget.kind() != GrugWidgetKind.CHECKBOX) return widget.contains(x, y);
        String label = widget.text();
        int width = CHECKBOX_BOX + 4;
        if (label != null && !label.isEmpty()) {
            width += fontRenderer.getStringWidth(label);
        }
        int left = round(widget.x());
        int top = round(widget.y());
        return x >= left && x < left + width && y >= top && y < top + CHECKBOX_BOX;
    }

    /**
     * The hand-drawn checkbox: this version has none, so its box and tick are drawn here, at the
     * box's own size whatever rectangle the mod declared.
     */
    private void drawCheckbox(GrugWidget widget) {
        int left = round(widget.x());
        int top = round(widget.y());
        int right = left + CHECKBOX_BOX;
        int bottom = top + CHECKBOX_BOX;
        Gui.drawRect(left, top, right, bottom, CHECKBOX_BORDER);
        Gui.drawRect(left + 1, top + 1, right - 1, bottom - 1, CHECKBOX_INTERIOR);
        if (widget.checked()) {
            drawTick(left, top, CHECKBOX_BOX, CHECKBOX_BOX);
        }
        String label = widget.text();
        if (label != null && !label.isEmpty()) {
            fontRenderer.drawStringWithShadow(
                    label,
                    right + 4,
                    top + (CHECKBOX_BOX - 8) / 2,
                    widget.active() ? LABEL : LABEL_DISABLED);
        }
    }

    /**
     * A tick made of two stepped diagonals, because this version draws a rectangle at a time. It
     * sits in the box's own pixels so a box of any size keeps the mark inside it.
     */
    private static void drawTick(int left, int top, int width, int height) {
        int step = Math.max(1, Math.min(width, height) / 10);
        int startX = left + width / 5;
        int startY = top + height / 2;
        for (int i = 0; i < 3; i++) {
            Gui.drawRect(
                    startX + i * step,
                    startY + i * step,
                    startX + i * step + step + 1,
                    startY + i * step + 2 * step,
                    CHECKBOX_MARK);
        }
        int kneeX = startX + 3 * step;
        int kneeY = startY + 3 * step;
        for (int i = 0; i < 4; i++) {
            Gui.drawRect(
                    kneeX + i * step,
                    kneeY - i * step - step,
                    kneeX + i * step + step + 1,
                    kneeY - i * step + step,
                    CHECKBOX_MARK);
        }
    }

    /**
     * The list: this version's own list is not placeable, so its rows and chrome are drawn here.
     */
    private void drawList(GrugWidget widget) {
        int left = round(widget.x());
        int top = round(widget.y());
        int right = left + Math.max(1, round(widget.width()));
        int bottom = top + Math.max(1, round(widget.height()));
        Gui.drawRect(left, top, right, bottom, LIST_BACKGROUND);
        int rowHeight = Math.max(1, round(widget.rowHeight()));
        enableClip(left, top, right, bottom);
        try {
            for (GrugWidget.Row row : widget.frameRows()) {
                int rowTop =
                        top
                                + round(
                                        GrugListMath.rowY(
                                                row.index(), widget.scroll(), widget.rowHeight()));
                if (row.index() == widget.selected()) {
                    Gui.drawRect(left, rowTop, right, rowTop + rowHeight, LIST_SELECTION);
                }
                int rgb = row.rgb() < 0 ? ROW_TEXT : row.rgb();
                fontRenderer.drawStringWithShadow(
                        row.text(), left + 5, rowTop + (rowHeight - 8) / 2, 0xFF000000 | rgb);
                Gui.drawRect(
                        left, rowTop + rowHeight - 1, right, rowTop + rowHeight, LIST_SEPARATOR);
            }
        } finally {
            disableClip();
        }
        drawScrollbar(widget, left, top, right, bottom);
    }

    /** The scrollbar, drawn only while the rows are taller than the list. */
    private void drawScrollbar(GrugWidget widget, int left, int top, int right, int bottom) {
        double max = GrugListMath.maxScroll(widget.rows(), widget.rowHeight(), widget.height());
        if (!(max > 0.0)) return;
        int trackHeight = bottom - top;
        double rowsHeight = widget.rows() * widget.rowHeight();
        int thumbHeight = Math.max(8, round(trackHeight * (trackHeight / rowsHeight)));
        thumbHeight = Math.min(thumbHeight, trackHeight);
        int thumbTop = top + round((trackHeight - thumbHeight) * (widget.scroll() / max));
        int barLeft = right - 6;
        Gui.drawRect(barLeft, top, right, bottom, SCROLLBAR_TRACK);
        Gui.drawRect(barLeft, thumbTop, right, thumbTop + thumbHeight, SCROLLBAR_THUMB);
    }

    /**
     * Clips drawing to a GUI-scaled rectangle. This version's own code never clips, so the scissor
     * is opened and closed here and no other element is expected to have one open.
     */
    private void enableClip(int left, int top, int right, int bottom) {
        ScaledResolution resolution =
                new ScaledResolution(mc.gameSettings, mc.displayWidth, mc.displayHeight);
        int scale = resolution.scaleFactor;
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(
                left * scale,
                mc.displayHeight - bottom * scale,
                Math.max(0, right - left) * scale,
                Math.max(0, bottom - top) * scale);
    }

    private static void disableClip() {
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
    }

    private static int round(double value) {
        return (int) Math.round(value);
    }

    private static int[] bounds(GrugWidget widget) {
        return new int[] {
            round(widget.x()),
            round(widget.y()),
            Math.max(1, round(widget.width())),
            Math.max(1, round(widget.height()))
        };
    }

    private static boolean sameBounds(int[] first, int[] second) {
        if (first == null || second == null) return false;
        for (int i = 0; i < first.length; i++) {
            if (first[i] != second[i]) return false;
        }
        return true;
    }

    /**
     * The game's slider for one the mod declared: the same button face, track and handle as this
     * version's own {@code GuiSlider}, but the value is the mod's and no game option is written.
     */
    private static final class GrugSliderButton extends GuiButton {
        private final GrugOwnedScreen screen;
        private final GrugWidget widget;

        /** Is the handle being held, which this version's button has no field for. */
        private boolean dragging;

        GrugSliderButton(int id, GrugOwnedScreen screen, GrugWidget widget) {
            super(
                    id,
                    round(widget.x()),
                    round(widget.y()),
                    Math.max(1, round(widget.width())),
                    Math.max(1, round(widget.height())),
                    widget.sliderLabel());
            this.screen = screen;
            this.widget = widget;
        }

        @Override
        protected int getHoverState(boolean hovering) {
            // The reference's slider draws the same face whether or not the mouse is over it.
            return 0;
        }

        @Override
        protected void mouseDragged(Minecraft minecraft, int x, int y) {
            if (!this.drawButton) return;
            if (this.dragging) {
                report(x);
                this.displayString = widget.sliderLabel();
            }
            GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
            int handle = this.xPosition + round(widget.sliderFraction() * (this.width - 8));
            this.drawTexturedModalRect(handle, this.yPosition, 0, 66, 4, 20);
            this.drawTexturedModalRect(handle + 4, this.yPosition, 196, 66, 4, 20);
        }

        @Override
        public boolean mousePressed(Minecraft minecraft, int x, int y) {
            if (!super.mousePressed(minecraft, x, y)) return false;
            report(x);
            this.dragging = true;
            return true;
        }

        @Override
        public void mouseReleased(int x, int y) {
            this.dragging = false;
        }

        /** The fraction the handle was left at, which core turns into the mod's value. */
        private void report(int x) {
            double span = this.width - 8;
            if (span <= 0.0) return;
            double fraction = (x - (this.xPosition + 4)) / span;
            if (fraction < 0.0) fraction = 0.0;
            if (fraction > 1.0) fraction = 1.0;
            GrugOwnedScreens.sliderMoved(screen, widget, fraction);
        }
    }
}
