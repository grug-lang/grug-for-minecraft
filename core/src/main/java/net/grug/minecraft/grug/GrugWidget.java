package net.grug.minecraft.grug;

import java.util.ArrayList;
import java.util.List;

/**
 * One widget a mod's screen declared, holding both what the mod set and what the player's last
 * action left, so the frame and {@code screen_widget_changed} read the same value.
 *
 * <p>The model is shared by every loader: a loader mirrors it into its own widget (a vanilla
 * button, checkbox, slider, text field, or its list) and reports a player's action back through the
 * {@code apply*} methods, which change the model before core asks {@code screen_widget_changed}. A
 * mod's own {@code set_*} changes the model only, which is why writing a value never asks the
 * export.
 */
public final class GrugWidget {

    /** One row as the frame draws it: what {@code screen_list_row} answered for that row. */
    public static final class Row {
        final int index;
        final String text;
        final int rgb;

        Row(int index, String text, int rgb) {
            this.index = index;
            this.text = text;
            this.rgb = rgb;
        }

        public int index() {
            return index;
        }

        public String text() {
            return text;
        }

        /** The row's colour, without an alpha: the loader draws it opaque. */
        public int rgb() {
            return rgb;
        }
    }

    final GrugOwnedScreen screen;
    final GrugWidgetKind kind;
    final String name;

    long entityId;

    double x;
    double y;
    double width;
    double height;
    String text;
    boolean active = true;
    boolean visible = true;

    boolean checked;

    double min;
    double max;
    double value;

    int rows;
    int selected = -1;
    double rowHeight;
    double scroll;

    int textColour = 14737632;
    long textRevision = 0;

    private final List<Row> frameRows = new ArrayList<>();

    GrugWidget(GrugOwnedScreen screen, GrugWidgetKind kind, String name) {
        this.screen = screen;
        this.kind = kind;
        this.name = name;
    }

    public GrugWidgetKind kind() {
        return kind;
    }

    public String name() {
        return name;
    }

    public long entityId() {
        return entityId;
    }

    public double x() {
        return x;
    }

    public double y() {
        return y;
    }

    public double width() {
        return width;
    }

    public double height() {
        return height;
    }

    public String text() {
        return text;
    }

    public boolean active() {
        return active;
    }

    public boolean visible() {
        return visible;
    }

    public boolean checked() {
        return checked;
    }

    public double min() {
        return min;
    }

    public double max() {
        return max;
    }

    public double value() {
        return value;
    }

    public int rows() {
        return rows;
    }

    public int selected() {
        return selected;
    }

    public double rowHeight() {
        return rowHeight;
    }

    public double scroll() {
        return scroll;
    }

    public int textColour() {
        return textColour;
    }

    /** Changes whenever {@code set_text} or a {@code screen_init} declaration set a new value. */
    public long textRevision() {
        return textRevision;
    }

    /** The rows {@code screen_list_row} answered for the frame that is being drawn. */
    public List<Row> frameRows() {
        return frameRows;
    }

    /** The message a slider draws: its text and its value rounded to tenths. */
    public String sliderLabel() {
        return GrugSliderMath.label(text, value);
    }

    /** The fraction of the slider's track the handle is drawn at. The value is already in range. */
    public double sliderFraction() {
        return GrugSliderMath.drawnFraction(GrugSliderMath.fraction(value, min, max));
    }

    /** Whether the point is inside the widget's rectangle. */
    public boolean contains(double mouseX, double mouseY) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    /**
     * Records what a player's click on the list selected, then answers whether the index named a
     * row of it, which is what asks {@code screen_widget_changed}.
     *
     * <p>A click on the row that is already selected is still a click the mod hears: the
     * reference's row callback runs on every click, so it can close a dropdown and re-apply the
     * row's value even when the selection did not move. Only an index outside the rows is not a
     * click on the list.
     */
    boolean applyListClick(int index) {
        if (index < 0 || index >= rows) return false;
        selected = index;
        return true;
    }

    /** Records a player ticking or unticking a checkbox. */
    boolean applyChecked(boolean nowChecked) {
        if (checked == nowChecked) return false;
        checked = nowChecked;
        return true;
    }

    /**
     * Records a player moving a slider, from the fraction of the track the handle was left at, and
     * answers whether that moved it to a value it did not hold.
     */
    boolean applySliderFraction(double fraction) {
        double before = value;
        value = GrugSliderMath.clamp(GrugSliderMath.value(fraction, min, max), min, max);
        return value != before;
    }

    /** Records a player editing a text field, and answers whether the text changed. */
    boolean applyText(String newText) {
        String now = newText == null ? "" : newText;
        if (now.equals(text)) return false;
        text = now;
        textRevision++;
        return true;
    }

    /**
     * Sets how many rows the list has, dropping a selection and scroll the new count cannot hold.
     */
    void applyRows(int count) {
        rows = count;
        selected = GrugListMath.selectionAfterRowsChange(selected, count);
        scroll = GrugListMath.clampScroll(scroll, rows, rowHeight, height);
    }

    /** Scrolls the list by a wheel movement, positive up and away. */
    boolean applyScroll(double delta) {
        double before = scroll;
        scroll = GrugListMath.scrollBy(scroll, delta, rows, rowHeight, height);
        return scroll != before;
    }

    /**
     * A mod's own {@code set_text}, which is silent and reaches the text field as a new value.
     *
     * <p>The revision moves only when the text does, so a {@code screen_init} that declares a field
     * again after a resize with the same text leaves the field's caret and selection alone.
     */
    void setText(String newText) {
        String applied = newText == null ? "" : newText;
        if (applied.equals(text)) return;
        text = applied;
        textRevision++;
    }

    /** A mod's own {@code set_position}, which keeps the widget's size. */
    void setPosition(double newX, double newY) {
        x = newX;
        y = newY;
    }

    /** A mod's own {@code set_active}. */
    void setActive(boolean nowActive) {
        active = nowActive;
    }

    /** A mod's own {@code set_visible}. */
    void setVisible(boolean nowVisible) {
        visible = nowVisible;
    }

    /** A mod's own {@code set_checked}. */
    void setChecked(boolean nowChecked) {
        checked = nowChecked;
    }

    /**
     * A mod's own {@code set_value}, brought into the slider's range, which does not move the min
     * or the max. The game's own slider clamps a value it is given, so this answers the value the
     * handle is drawn at rather than the value a mod named.
     */
    void setValue(double newValue) {
        value = GrugSliderMath.clamp(newValue, min, max);
    }

    /** A mod's own {@code set_text_colour}, which keeps the colour without its alpha. */
    void setTextColour(int rgb) {
        textColour = rgb & 0xFFFFFF;
    }

    /** A mod's own {@code select}, which is silent and moves the list's selection. */
    boolean select(int index) {
        if (index < 0 || index >= rows) return false;
        selected = index;
        return true;
    }

    /** Whether the widget has the kind the call needs. */
    boolean is(GrugWidgetKind kind) {
        return this.kind == kind;
    }

    void clearFrameRows() {
        frameRows.clear();
    }

    void addFrameRow(int index, String rowText, int rgb) {
        frameRows.add(new Row(index, rowText, rgb));
    }
}
