package net.grug.minecraft.stationapi.client;

import net.grug.minecraft.core.GrugRunWindow;
import net.grug.minecraft.grug.GrugListMath;
import net.grug.minecraft.grug.GrugOverlay;
import net.grug.minecraft.grug.GrugOverlayGeometry;
import net.grug.minecraft.grug.GrugOwnedScreen;
import net.grug.minecraft.grug.GrugOwnedScreens;
import net.grug.minecraft.grug.GrugWidget;
import net.grug.minecraft.grug.GrugWidgetKind;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.util.ScreenScaler;
import net.minecraft.util.CharacterUtils;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The game's screen for one screen a mod owns: it mirrors the mod's widgets into Beta 1.7.3's own
 * and hands every frame and input event back to core, which asks the mod's {@code screen_*}
 * exports.
 *
 * <p>Core owns the lifecycle, the widget model and the ordering: this class adds only what this
 * version has to add. A button is a vanilla {@link ButtonWidget}, and a slider subclasses it with
 * the same face and label, drawing its handle between the two, because this version's own slider is
 * a game option's widget and writes the option it was built for. This version has no checkbox and
 * no placeable list, so both are drawn by hand from the model, and the wheel is taken in this
 * class's own mouse handler, which overrides the one the shared mixin hooks. Its text field only
 * appends and backspaces, with no caret and no selection, so the field is drawn and edited here as
 * well, against the contract {@code Screen.add_text_field} promises.
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
public final class GrugOwnedScreenHost extends Screen implements GrugScreenGeometry {

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

    /** The border and interior of the hand-drawn text field, as this version's own field draws. */
    private static final int FIELD_BORDER = 0xFFA0A0A0;

    private static final int FIELD_BACKGROUND = 0xFF000000;

    /** The block a field draws over the characters a selection covers. */
    private static final int FIELD_SELECTION = 0xFF0000FF;

    /** The caret a focused field draws, as a line at the character the next one lands before. */
    private static final int FIELD_CARET = 0xFFD0D0D0;

    /** The label colour of a control the game draws as a plain label. */
    private static final int LABEL = 14737632;

    /** The label colour of a control that is not active. */
    private static final int LABEL_DISABLED = -6250336;

    /**
     * The longest text a field takes from the player. The mod's text is the truth and a value it
     * set is never truncated behind its back, so the limit is far above what a field holds in
     * practice; it only keeps a paste of unbounded length from making every later frame slow.
     */
    private static final int TEXT_FIELD_MAX_LENGTH = 1024;

    private final GrugOwnedScreen screen;

    /** The vanilla button for each button or slider widget, in declaration order. */
    private final Map<GrugWidget, GrugButton> buttonsByWidget = new LinkedHashMap<>();

    /** Which widget each button in the screen's control list belongs to. */
    private final Map<ButtonWidget, GrugWidget> widgetsByButton = new LinkedHashMap<>();

    /** The sliders, so a drag follows the mouse between its press and its release. */
    private final List<GrugSliderButton> sliders = new ArrayList<>();

    /**
     * The hand-written field for each text field widget. They outlive a re-init, so a resize does
     * not lose the caret, the selection or the keyboard.
     */
    private final Map<GrugWidget, Field> fields = new LinkedHashMap<>();

    /** The next id for a button in the screen's control list, which only has to be unique here. */
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
    public void init() {
        // A resize asks screen_init again, so the widgets it declares this time are the ones
        // mirrored. The game set the screen's width and height before this call.
        super.init();
        GrugOwnedScreens.initScreen(screen, width, height);
        rebuildMirrors();
    }

    /**
     * Makes a mirror for every widget the mod declared, in the order it declared them, so the frame
     * draws and the presses reach them in that order.
     *
     * <p>Only a button's label, and a slider's label with its value, is copied into a mirror: a
     * checkbox and a list carry no label of their own, and a list's {@code text} is null, which a
     * button would draw as nothing while a null reaching a font is a defect waiting for a caller.
     */
    private void rebuildMirrors() {
        buttons.clear();
        buttonsByWidget.clear();
        widgetsByButton.clear();
        sliders.clear();
        fields.keySet().retainAll(screen.widgets());
        for (GrugWidget widget : screen.widgets()) {
            switch (widget.kind()) {
                case BUTTON:
                    addButton(new GrugButton(nextButtonId++, widget), widget);
                    break;
                case SLIDER:
                    GrugSliderButton slider = new GrugSliderButton(nextButtonId++, screen, widget);
                    addButton(slider, widget);
                    sliders.add(slider);
                    break;
                case TEXT_FIELD:
                    if (!fields.containsKey(widget)) fields.put(widget, new Field(widget));
                    break;
                default:
                    break;
            }
        }
    }

    private void addButton(GrugButton button, GrugWidget widget) {
        buttons.add(button);
        buttonsByWidget.put(widget, button);
        widgetsByButton.put(button, widget);
    }

    /**
     * Copies what the mod changed outside {@code screen_init} into the mirrors before the frame: a
     * moved widget, a new label or value, a shown or hidden one, and a field's own text.
     */
    private void syncWidgets() {
        for (GrugWidget widget : screen.widgets()) {
            if (widget.kind() == GrugWidgetKind.TEXT_FIELD) {
                Field field = fields.get(widget);
                if (field != null) field.syncFrom(widget);
                continue;
            }
            GrugButton button = buttonsByWidget.get(widget);
            if (button == null) continue;
            button.setBounds(round(widget.x()), round(widget.y()), widget.width(), widget.height());
            // This version's press path checks {@code active} only, so a hidden mirror is made
            // inactive as well: a widget the mod hid must not take a press the player never saw.
            button.active = widget.active() && widget.visible();
            button.visible = widget.visible();
            if (widget.kind() == GrugWidgetKind.SLIDER) {
                button.text = widget.sliderLabel();
            } else {
                button.text = widget.text() == null ? "" : widget.text();
            }
        }
    }

    @Override
    public void tick() {
        super.tick();
        for (Field field : fields.values()) {
            field.blink++;
        }
        // A slider follows the mouse between the press and the release, which is not an event this
        // version's screens are told about. The drag ends as soon as the button is up, so a click a
        // test delivered without a release cannot keep moving the handle on later ticks.
        if (!Mouse.isButtonDown(0)) {
            for (GrugSliderButton slider : sliders) {
                slider.stopDrag();
            }
            return;
        }
        double mouseX = (double) Mouse.getX() * width / minecraft.displayWidth;
        for (GrugSliderButton slider : sliders) {
            slider.dragTo(mouseX);
        }
    }

    @Override
    public void render(int mouseX, int mouseY, float tickDelta) {
        // This is the frame a test of the screen's mod waits on.
        GrugOverlay.noteFrame(this);
        // A test that parked the cursor for a capture is where the frame must see the mouse: this
        // version reads its pointer from LWJGL's own position, which a warp does not always move,
        // so the parked point wins when a test set one. The press handlers below keep the event's
        // own position, which is where the player really pressed.
        double[] parked =
                GrugRunWindow.parkedMousePosition(
                        minecraft.displayWidth, minecraft.displayHeight, width, height);
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
        renderBackground();
        for (GrugWidget widget : screen.widgets()) {
            if (!widget.visible()) continue;
            GrugWidgetKind kind = widget.kind();
            if (kind == GrugWidgetKind.BUTTON || kind == GrugWidgetKind.SLIDER) {
                GrugButton button = buttonsByWidget.get(widget);
                if (button != null) button.render(minecraft, mouseX, mouseY);
            } else if (kind == GrugWidgetKind.CHECKBOX) {
                drawCheckbox(widget);
            } else if (kind == GrugWidgetKind.TEXT_FIELD) {
                drawField(widget);
            } else if (kind == GrugWidgetKind.LIST) {
                drawList(widget);
            }
        }
        // screen_render is asked after the background and the widgets, so a mod draws its labels
        // and titles on top of them.
        GrugOverlayGeometry geometry =
                GrugOwnedScreens.renderScreen(screen, width, height, mouseX, mouseY);
        GrugOverlayHost.drawGeometry(this, minecraft, geometry);
        GrugOwnedScreens.noteScreenFrame(screen);
    }

    @Override
    protected void mouseClicked(int x, int y, int button) {
        // The mod sees the press before the widget under the mouse, and take() keeps it from the
        // widget.
        if (GrugOwnedScreens.clickScreen(
                screen, width, height, x, y, button, GrugOverlayHost.shiftHeld())) {
            GrugOverlay.noteTestPressTaken();
            return;
        }
        // The mod may have shown, hidden or moved a widget during that call, and the mirrors are a
        // frame behind. A press is routed by a mirror's own visibility, bounds and active flag, so
        // the mirrors are brought up to date first: otherwise a list the mod just hid still takes
        // the press meant for the widget it revealed underneath, which a player pressing twice
        // within one frame reaches and a test pressing on two ticks does too, because the runner
        // runs many ticks per frame.
        syncWidgets();
        for (GrugWidget widget : screen.widgets()) {
            if (!widget.visible() || !widget.active()) continue;
            if (!pressReaches(widget, x, y)) continue;
            if (widget.kind() == GrugWidgetKind.TEXT_FIELD) {
                Field field = fields.get(widget);
                if (field != null) {
                    // One field has the keyboard at a time, so the others give it up first.
                    releaseFields(field);
                    field.click(caretIndexAt(widget, field, x));
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
            // A button or a slider: the screen's own press path in the control list takes it.
            break;
        }
        super.mouseClicked(x, y, button);
        // A click on an empty spot takes the keyboard away from a focused field, as the API says,
        // and so does a click on a control that is not a field.
        releaseFields(null);
    }

    @Override
    protected void mouseReleased(int x, int y, int button) {
        super.mouseReleased(x, y, button);
        for (GrugSliderButton slider : sliders) {
            slider.stopDrag();
        }
    }

    /**
     * The screen's mouse handler. It is overridden rather than inherited because this version's own
     * handler looks at the buttons alone, and the shared mixin's wheel hook is on the inherited
     * method, which an override replaces.
     */
    @Override
    public void onMouseEvent() {
        if (GrugOverlayHost.wheelOverOwnedScreen(
                this, minecraft.displayWidth, minecraft.displayHeight)) {
            return;
        }
        int button = Mouse.getEventButton();
        if (button < 0) return;
        int x = Mouse.getEventX() * width / minecraft.displayWidth;
        int y = height - Mouse.getEventY() * height / minecraft.displayHeight - 1;
        if (Mouse.getEventButtonState()) {
            mouseClicked(x, y, button);
        } else {
            mouseReleased(x, y, button);
        }
    }

    @Override
    protected void keyPressed(char character, int key) {
        pressKey(character, key);
    }

    /**
     * Delivers one press. Escape is always asked of {@code screen_key}, whatever has the keyboard,
     * because the game closes the screen with it and a mod that runs its own close from that export
     * would otherwise never hear of the close. Any other key goes to a focused field, and the mod
     * is not asked while a field has the keyboard. A press no field and no export took falls to the
     * screen, where Escape closes it. Answers whether the press was used up, which is always true
     * because Escape is the screen's own close.
     */
    boolean pressKey(char character, int key) {
        if (key == Keyboard.KEY_ESCAPE) {
            if (GrugOverlayHost.pressOwnedScreenKey(this, character, key)) return true;
            closeScreen();
            return true;
        }
        Field focused = focusedField();
        if (focused != null) {
            if (focused.edit(character, key)) reportFieldText(focused);
            return true;
        }
        GrugOverlayHost.pressOwnedScreenKey(this, character, key);
        return true;
    }

    /**
     * Asks the mod's {@code screen_scroll} for one wheel movement and then the list under the
     * point, which is the order the API gives. Answers whether the export took the movement.
     */
    boolean scrollAt(int x, int y, double delta) {
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
    private Field focusedField() {
        for (Field field : fields.values()) {
            if (field.focused) return field;
        }
        return null;
    }

    /** Takes the keyboard from every field but {@code keep}, which may be null. */
    private void releaseFields(Field keep) {
        for (Field field : fields.values()) {
            if (field != keep) field.focused = false;
        }
    }

    /** Tells core about an edit a field made, and only when the text really changed. */
    private void reportFieldText(Field field) {
        for (Map.Entry<GrugWidget, Field> entry : fields.entrySet()) {
            if (entry.getValue() != field) continue;
            GrugWidget widget = entry.getKey();
            if (!field.text.equals(widget.text())) {
                GrugOwnedScreens.textEdited(screen, widget, field.text);
            }
            return;
        }
    }

    /**
     * The index a press at {@code mouseX} lands the caret before, counted from the text the frame
     * draws: the last character whose middle is left of the point.
     */
    private int caretIndexAt(GrugWidget widget, Field field, int mouseX) {
        int textX = round(widget.x()) + 4;
        int innerWidth = Math.max(0, round(widget.width()) - 8);
        int first = firstShown(field, innerWidth);
        int offset = mouseX - textX;
        int width = 0;
        for (int index = first; index < field.text.length(); index++) {
            int characterWidth = textRenderer.getWidth(field.text.substring(index, index + 1));
            if (offset < width + characterWidth / 2) return index;
            width += characterWidth;
        }
        return field.text.length();
    }

    /** The first character the frame draws, so the caret stays inside the field's width. */
    private int firstShown(Field field, int innerWidth) {
        int first = 0;
        while (first < field.caret
                && textRenderer.getWidth(field.text.substring(first, field.caret)) > innerWidth) {
            first++;
        }
        return first;
    }

    /** Closes this screen the way {@code close_screen} does on this loader. */
    private void closeScreen() {
        if (minecraft.currentScreen == this) {
            // The screen Escape gives up: this version shows the world again, and locks the mouse
            // with it, and the in-game menu is its own Escape when no screen is open.
            minecraft.setScreen(null);
        }
    }

    @Override
    public void removed() {
        super.removed();
        // The game takes the screen away for Escape, for a screen it put in its place, and for the
        // world being left, and each of those is the same end for the mod. A screen the mod closed
        // itself is already ended, so only one that is still open is noted, and the end waits for
        // the next client tick: this version calls this inside `setScreen` before it assigns the
        // replacement, so a screen_closed that opens another screen would have it dropped. Core
        // keeps the note and `runGameRemovals` ends it from the tick.
        if (GrugOwnedScreens.openScreen() == screen && screen.generation() == generation) {
            GrugOwnedScreens.noteGameRemoved(screen, generation);
        }
    }

    @Override
    protected void buttonClicked(ButtonWidget button) {
        // A slider reports the press and the drag itself, and this version's press path calls this
        // after it.
        if (button instanceof GrugSliderButton) return;
        GrugWidget widget = widgetsByButton.get(button);
        if (widget != null) {
            GrugOwnedScreens.buttonPressed(screen, widget);
        }
    }

    @Override
    public boolean shouldPause() {
        // The world must keep ticking: a mod screen is drawn over a world someone builds in.
        return false;
    }

    // --- What a test asks the screen ------------------------------------------------------------

    @Override
    public int grug$width() {
        return width;
    }

    @Override
    public int grug$height() {
        return height;
    }

    @Override
    public int grug$guiLeft() {
        // A mod screen is the whole window, so its GUI starts at the corner and covers all of it.
        return 0;
    }

    @Override
    public int grug$guiTop() {
        return 0;
    }

    @Override
    public int grug$guiWidth() {
        return width;
    }

    @Override
    public int grug$guiHeight() {
        return height;
    }

    @Override
    public boolean grug$click(int mouseX, int mouseY, int button) {
        GrugOverlay.beginTestPress();
        mouseClicked(mouseX, mouseY, button);
        return GrugOverlay.testPressTaken();
    }

    @Override
    public boolean grug$pressKey(char character, int key) {
        GrugOverlay.beginTestPress();
        pressKey(character, key);
        return GrugOverlay.testPressTaken();
    }

    @Override
    public boolean grug$scroll() {
        GrugOverlay.beginTestPress();
        onMouseEvent();
        return GrugOverlay.testPressTaken();
    }

    // --- Drawing the widgets this version has none of -------------------------------------------

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
            width += textRenderer.getWidth(label);
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
        fill(left, top, right, bottom, CHECKBOX_BORDER);
        fill(left + 1, top + 1, right - 1, bottom - 1, CHECKBOX_INTERIOR);
        if (widget.checked()) {
            drawTick(left, top, CHECKBOX_BOX, CHECKBOX_BOX);
        }
        String label = widget.text();
        if (label != null && !label.isEmpty()) {
            textRenderer.drawWithShadow(
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
    private void drawTick(int left, int top, int width, int height) {
        int step = Math.max(1, Math.min(width, height) / 10);
        int startX = left + width / 5;
        int startY = top + height / 2;
        for (int i = 0; i < 3; i++) {
            fill(
                    startX + i * step,
                    startY + i * step,
                    startX + i * step + step + 1,
                    startY + i * step + 2 * step,
                    CHECKBOX_MARK);
        }
        int kneeX = startX + 3 * step;
        int kneeY = startY + 3 * step;
        for (int i = 0; i < 4; i++) {
            fill(
                    kneeX + i * step,
                    kneeY - i * step - step,
                    kneeX + i * step + step + 1,
                    kneeY - i * step + step,
                    CHECKBOX_MARK);
        }
    }

    /**
     * The list: this version's own list is an abstract widget a screen sizes in its constructor, so
     * its rows and chrome are drawn here.
     */
    private void drawList(GrugWidget widget) {
        int left = round(widget.x());
        int top = round(widget.y());
        int right = left + Math.max(1, round(widget.width()));
        int bottom = top + Math.max(1, round(widget.height()));
        fill(left, top, right, bottom, LIST_BACKGROUND);
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
                    fill(left, rowTop, right, rowTop + rowHeight, LIST_SELECTION);
                }
                int rgb = row.rgb() < 0 ? ROW_TEXT : row.rgb();
                textRenderer.drawWithShadow(
                        row.text(), left + 5, rowTop + (rowHeight - 8) / 2, 0xFF000000 | rgb);
                fill(left, rowTop + rowHeight - 1, right, rowTop + rowHeight, LIST_SEPARATOR);
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
        fill(barLeft, top, right, bottom, SCROLLBAR_TRACK);
        fill(barLeft, thumbTop, right, thumbTop + thumbHeight, SCROLLBAR_THUMB);
    }

    /**
     * The text field: this version's own field appends and backspaces with no caret and no
     * selection, so the frame draws the field, its selection and its caret here.
     */
    private void drawField(GrugWidget widget) {
        Field field = fields.get(widget);
        if (field == null) return;
        int left = round(widget.x());
        int top = round(widget.y());
        int right = left + Math.max(1, round(widget.width()));
        int bottom = top + Math.max(1, round(widget.height()));
        fill(left - 1, top - 1, right + 1, bottom + 1, FIELD_BORDER);
        fill(left, top, right, bottom, FIELD_BACKGROUND);
        int colour = widget.active() ? 0xFF000000 | widget.textColour() : LABEL_DISABLED;
        int textX = left + 4;
        int textY = top + (bottom - top - 8) / 2;
        int innerWidth = Math.max(0, right - textX - 4);
        int first = firstShown(field, innerWidth);
        String shown = field.text.substring(first);
        enableClip(left, top, right, bottom);
        try {
            if (field.focused && field.hasSelection()) {
                int low = Math.max(first, field.selectionLow());
                int high = Math.max(low, field.selectionHigh());
                int x1 = textX + textRenderer.getWidth(field.text.substring(first, low));
                int x2 = textX + textRenderer.getWidth(field.text.substring(first, high));
                fill(x1, top + 2, Math.min(x2, right), bottom - 2, FIELD_SELECTION);
            }
            textRenderer.drawWithShadow(shown, textX, textY, colour);
            if (field.focused && field.blink / 6 % 2 == 0) {
                int caretX =
                        Math.min(
                                textX
                                        + textRenderer.getWidth(
                                                field.text.substring(first, field.caret)),
                                right - 1);
                fill(caretX, top + 2, caretX + 1, bottom - 2, FIELD_CARET);
            }
        } finally {
            disableClip();
        }
    }

    /**
     * Clips drawing to a GUI-scaled rectangle. This version's own code never clips, so the scissor
     * is opened and closed here and no other element is expected to have one open.
     */
    private void enableClip(int left, int top, int right, int bottom) {
        ScreenScaler window =
                new ScreenScaler(
                        minecraft.options, minecraft.displayWidth, minecraft.displayHeight);
        int scale = window.scaleFactor;
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(
                left * scale,
                minecraft.displayHeight - bottom * scale,
                Math.max(0, right - left) * scale,
                Math.max(0, bottom - top) * scale);
    }

    private static void disableClip() {
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
    }

    private static int round(double value) {
        return (int) Math.round(value);
    }

    /**
     * The game's button for one the mod declared, with a place to keep the size the mod last set,
     * which this version's own button keeps out of reach.
     */
    private static class GrugButton extends ButtonWidget {

        GrugButton(int id, GrugWidget widget) {
            super(
                    id,
                    round(widget.x()),
                    round(widget.y()),
                    Math.max(1, round(widget.width())),
                    Math.max(1, round(widget.height())),
                    widget.text() == null ? "" : widget.text());
        }

        void setBounds(int x, int y, double width, double height) {
            this.x = x;
            this.y = y;
            this.width = Math.max(1, round(width));
            this.height = Math.max(1, round(height));
        }
    }

    /**
     * The game's slider for one the mod declared: the same button face and label as this version's
     * own slider, with the handle at the mod's value and no game option behind it.
     */
    private static final class GrugSliderButton extends GrugButton {
        private final GrugOwnedScreen screen;
        private final GrugWidget widget;

        /** Is the handle being held, which this version's button has no field for. */
        private boolean dragging;

        GrugSliderButton(int id, GrugOwnedScreen screen, GrugWidget widget) {
            super(id, widget);
            this.screen = screen;
            this.widget = widget;
        }

        @Override
        protected int getYImage(boolean hovered) {
            // The reference's slider draws the same face whether or not the mouse is over it.
            return 0;
        }

        /**
         * Draws the handle where this version's own slider does: inside the button's own render,
         * before the label is drawn over it, so a label that crosses the handle stays readable.
         */
        @Override
        protected void renderBackground(Minecraft minecraft, int mouseX, int mouseY) {
            if (!this.visible) return;
            GL11.glBindTexture(3553, minecraft.textureManager.getTextureId("/gui/gui.png"));
            GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
            int handle = this.x + round(this.widget.sliderFraction() * (this.width - 8));
            this.drawTexture(handle, this.y, 0, 66, 4, this.height);
            this.drawTexture(handle + 4, this.y, 196, 66, 4, this.height);
        }

        /**
         * This version's press path finds the control under the mouse through this method, so the
         * press and the drag are reported here, as its own slider does.
         */
        @Override
        public boolean isMouseOver(Minecraft minecraft, int mouseX, int mouseY) {
            if (!super.isMouseOver(minecraft, mouseX, mouseY)) return false;
            this.dragging = true;
            report(mouseX);
            return true;
        }

        @Override
        public void mouseReleased(int mouseX, int mouseY) {
            this.dragging = false;
        }

        /** Stops the drag when the mouse button that started it is no longer down. */
        void stopDrag() {
            this.dragging = false;
        }

        /** Follows the mouse while the handle is held. */
        void dragTo(double mouseX) {
            if (this.dragging) report(mouseX);
        }

        /** The fraction the handle was left at, which core turns into the mod's value. */
        private void report(double mouseX) {
            double span = this.width - 8;
            if (span <= 0.0) return;
            double fraction = (mouseX - (this.x + 4)) / span;
            if (fraction < 0.0) fraction = 0.0;
            if (fraction > 1.0) fraction = 1.0;
            GrugOwnedScreens.sliderMoved(screen, widget, fraction);
        }
    }

    /**
     * One text field's own state: the text the frame draws, the caret, the selection's other end
     * and the blink phase.
     *
     * <p>The mod's {@code text} is the truth. An edit lands here first and is reported to core,
     * which writes it into the widget, so the next frame's copy from the widget changes nothing.
     */
    private static final class Field {
        private String text;
        private long revision;
        private int caret;
        private int anchor;
        private int blink;
        private boolean focused;

        /** Whether the mod lets the player edit this field, as its widget's {@code active} says. */
        private boolean editable = true;

        Field(GrugWidget widget) {
            String value = widget.text() == null ? "" : widget.text();
            this.text = value;
            this.revision = widget.textRevision();
            this.caret = value.length();
            this.anchor = this.caret;
            this.editable = widget.active();
        }

        /** Takes a value the mod set outside {@code screen_init}, keeping the caret in the text. */
        void syncFrom(GrugWidget widget) {
            editable = widget.active();
            if (widget.textRevision() == revision) return;
            revision = widget.textRevision();
            String value = widget.text() == null ? "" : widget.text();
            if (value.equals(text)) return;
            text = value;
            caret = Math.min(caret, text.length());
            anchor = caret;
        }

        /** Puts the caret at the index the press landed on, and takes the keyboard. */
        void click(int index) {
            focused = true;
            blink = 0;
            caret = Math.max(0, Math.min(index, text.length()));
            anchor = caret;
        }

        boolean hasSelection() {
            return anchor != caret;
        }

        int selectionLow() {
            return Math.min(anchor, caret);
        }

        int selectionHigh() {
            return Math.max(anchor, caret);
        }

        /**
         * Applies one press and answers whether the text changed: an arrow, Home or End moves the
         * caret, the delete keys remove a character or the selection, and a printable character or
         * a paste replaces the selection. Ctrl+A, Ctrl+C, Ctrl+X and Ctrl+V are this version's own
         * edit keys, as its text field has them.
         */
        boolean edit(char character, int key) {
            // A field the mod disabled takes no edit, exactly as this version's own field does.
            if (!editable) return false;
            boolean shift = GrugOverlayHost.shiftHeld();
            boolean ctrl = GrugOverlayHost.controlHeld();
            blink = 0;
            if (key == Keyboard.KEY_LEFT) {
                moveTo(caret - 1, shift);
                return false;
            }
            if (key == Keyboard.KEY_RIGHT) {
                moveTo(caret + 1, shift);
                return false;
            }
            if (key == Keyboard.KEY_HOME) {
                moveTo(0, shift);
                return false;
            }
            if (key == Keyboard.KEY_END) {
                moveTo(text.length(), shift);
                return false;
            }
            if (key == Keyboard.KEY_BACK) return deleteRange(caret - 1, caret);
            if (key == Keyboard.KEY_DELETE) return deleteRange(caret, caret + 1);
            if (ctrl) {
                if (key == Keyboard.KEY_A) {
                    anchor = 0;
                    caret = text.length();
                    return false;
                }
                if (key == Keyboard.KEY_V) return insert(clipboard());
                if (key == Keyboard.KEY_C) {
                    copySelection(false);
                    return false;
                }
                if (key == Keyboard.KEY_X) return copySelection(true);
                // Every other control chord types nothing, as the API says.
                return false;
            }
            if (CharacterUtils.VALID_CHARACTERS.indexOf(character) < 0) return false;
            if (character < ' ') return false;
            return insert(String.valueOf(character));
        }

        /** Moves the caret, extending the selection with shift as the API's field does. */
        private void moveTo(int index, boolean extend) {
            caret = Math.max(0, Math.min(index, text.length()));
            if (!extend) anchor = caret;
        }

        /** Removes the characters between two indexes, keeping the caret where they were. */
        private boolean deleteRange(int low, int high) {
            if (hasSelection()) return deleteSelection();
            low = Math.max(0, low);
            high = Math.min(high, text.length());
            if (low >= high) return false;
            text = text.substring(0, low) + text.substring(high);
            caret = low;
            anchor = low;
            return true;
        }

        /** Removes what the selection covers and puts the caret where it started. */
        private boolean deleteSelection() {
            int low = selectionLow();
            int high = selectionHigh();
            if (low >= high) return false;
            text = text.substring(0, low) + text.substring(high);
            caret = low;
            anchor = low;
            return true;
        }

        /** Writes {@code value} over the selection, or at the caret, up to the field's limit. */
        private boolean insert(String value) {
            if (value.isEmpty()) return false;
            int low = hasSelection() ? selectionLow() : caret;
            int high = hasSelection() ? selectionHigh() : caret;
            int room = TEXT_FIELD_MAX_LENGTH - (text.length() - (high - low));
            if (room <= 0) return false;
            if (value.length() > room) value = value.substring(0, room);
            text = text.substring(0, low) + value + text.substring(high);
            caret = low + value.length();
            anchor = caret;
            return true;
        }

        /** The system clipboard's text, which this version's own field pastes. */
        private String clipboard() {
            String value = Screen.getClipboard();
            return value == null ? "" : value;
        }

        /**
         * Copies the selection, or cuts it, and answers whether the text changed. A headless client
         * has no system clipboard to write, which is the same client whose paste reads nothing; the
         * cut still removes the text, which is what the player asked for.
         */
        private boolean copySelection(boolean cut) {
            if (!hasSelection()) return false;
            String selected = text.substring(selectionLow(), selectionHigh());
            try {
                Toolkit.getDefaultToolkit()
                        .getSystemClipboard()
                        .setContents(new StringSelection(selected), null);
            } catch (RuntimeException e) {
                // A run whose window has no clipboard still cuts, which is the edit the player
                // asked for; the paste that follows reads nothing, as it does there.
                return cut && deleteSelection();
            }
            return cut && deleteSelection();
        }
    }
}
