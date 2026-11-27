package net.grug.minecraft.grug;

/**
 * The pure arithmetic of a scrolling list: how far it may scroll, which rows its viewport shows,
 * which row a point belongs to, and what happens to its selection when the row count changes.
 *
 * <p>A list's scroll is a pixel offset from its first row, so a wheel notch moves it one row and a
 * trackpad's fraction of a notch moves it that fraction. The viewport shows every row that
 * intersects it, including a partly visible row at either end, which is what the reference's own
 * selection list draws.
 */
public final class GrugListMath {

    @GrugGenerated("utility class: never instantiated")
    private GrugListMath() {}

    /** The largest scroll the list has, or 0 when every row fits. */
    public static double maxScroll(int rows, double rowHeight, double height) {
        if (rows <= 0 || rowHeight <= 0.0 || height <= 0.0) return 0.0;
        return Math.max(0.0, rows * rowHeight - height);
    }

    /** {@code scroll} brought into the range the list can be scrolled to. */
    public static double clampScroll(double scroll, int rows, double rowHeight, double height) {
        double max = maxScroll(rows, rowHeight, height);
        if (!(scroll > 0.0)) return 0.0;
        return Math.min(scroll, max);
    }

    /** The scroll after turning the wheel by {@code delta} notches, positive up and away. */
    public static double scrollBy(
            double scroll, double delta, int rows, double rowHeight, double height) {
        if (delta == 0.0) return clampScroll(scroll, rows, rowHeight, height);
        return clampScroll(scroll - delta * rowHeight, rows, rowHeight, height);
    }

    /** The first row the viewport shows, or 0 for a list with no rows. */
    public static int firstVisible(double scroll, int rows, double rowHeight) {
        if (rows <= 0 || rowHeight <= 0.0) return 0;
        int first = (int) Math.floor(scroll / rowHeight);
        if (first < 0) return 0;
        return Math.min(first, rows);
    }

    /** The last row the viewport shows, or {@code first - 1} when it shows none. */
    public static int lastVisible(double scroll, int rows, double rowHeight, double height) {
        int first = firstVisible(scroll, rows, rowHeight);
        if (rows <= 0 || rowHeight <= 0.0 || height <= 0.0) return first - 1;
        int last = (int) Math.ceil((scroll + height) / rowHeight) - 1;
        // A scroll past the list's end can put the last row before the first, and the max answers
        // first - 1 then, the viewport that shows no row.
        return Math.min(Math.max(last, first - 1), rows - 1);
    }

    /** How many rows the viewport shows. */
    public static int visibleCount(double scroll, int rows, double rowHeight, double height) {
        int first = firstVisible(scroll, rows, rowHeight);
        int last = lastVisible(scroll, rows, rowHeight, height);
        return Math.max(0, last - first + 1);
    }

    /** The row a point inside the list belongs to, or -1 when it belongs to none. */
    public static int rowAt(double y, double height, double scroll, int rows, double rowHeight) {
        if (rows <= 0 || rowHeight <= 0.0 || !(height > 0.0)) return -1;
        if (!(y >= 0.0) || y >= height) return -1;
        int index = (int) Math.floor((scroll + y) / rowHeight);
        if (index < 0 || index >= rows) return -1;
        return index;
    }

    /** Where a row's top edge is drawn, as a distance from the list's own top edge. */
    public static double rowY(int index, double scroll, double rowHeight) {
        return index * rowHeight - scroll;
    }

    /** The selection left after the row count becomes {@code rows}: -1 when its row is gone. */
    public static int selectionAfterRowsChange(int selected, int rows) {
        if (selected < 0) return -1;
        return selected < rows ? selected : -1;
    }
}
