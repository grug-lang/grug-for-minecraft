package net.grug.minecraft.grug;

/**
 * The answer a list asks for one visible row each frame, passed to the screen's {@code
 * screen_list_row} export.
 *
 * <p>It is answered once with {@link #set_text}, and only the answer's text and colour are read
 * afterwards, so an export that returns without answering is an err even when it coloured the row.
 */
public final class GrugListRowResponse extends GrugResponse {
    private final int index;
    private final GrugWidget widget;
    private String text = "";
    private boolean coloured = false;
    private int rgb = 0;

    public GrugListRowResponse(int index, GrugWidget widget) {
        super("screen_list_row");
        this.index = index;
        this.widget = widget;
    }

    /** The row's number from 0, in the order the list numbers its rows. */
    public int index() {
        if (!checkOpen("ListRowResponse.index")) return 0;
        return index;
    }

    /** The list the row belongs to, so one export can answer for several lists. */
    public GrugWidget widget() {
        if (!checkOpen("ListRowResponse.widget")) return null;
        return widget;
    }

    /** Answers the row's text, which is also this response's one answer. */
    public void setText(String rowText) {
        if (!beginAnswer("ListRowResponse.set_text")) return;
        text = rowText == null ? "" : rowText;
    }

    /**
     * Colours the row's text, which is not an answer on its own: a row without text is not a row.
     */
    public void setTextColour(int colour) {
        if (!checkOpen("ListRowResponse.set_text_colour")) return;
        coloured = true;
        rgb = colour & 0xFFFFFF;
    }

    /** The text the export answered, valid only after {@link #finish()} returned true. */
    String answeredText() {
        return text;
    }

    /** The colour the export asked for, or -1 when it asked for none. */
    int answeredRgb() {
        return coloured ? rgb : -1;
    }
}
