package net.grug.minecraft.grug;

/**
 * The answer a list asks for one visible row each frame, passed to the screen's {@code
 * screen_list_row} export.
 *
 * <p>It is answered once with {@link #set_text}, and only the answer's text and colour are read
 * afterwards, so an export that returns without answering is an err even when it coloured the row.
 */
public final class GrugListRowResponse extends GrugResponse {
    /** Guards {@link #lastDefect}, which the render thread writes and a test's thread reads. */
    private static final Object defectLock = new Object();

    /** The defect a row export reported in the most recent frame, or null when none did. */
    private static String lastDefect = null;

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

    /**
     * Reports a broken rule of this response the way the mod's own {@code err} is reported: the
     * frame keeps drawing, and the message goes to the chat and to the test's record.
     *
     * <p>The host asks for a row between two frames, with no grug call of the running test around
     * it, so the base class's report would leave the defect for the runner to fail the test with on
     * its next tick, naming a test that did not make the call. The row is part of the screen's
     * frame instead, so a defect in it is bounded to that frame: the frame shows the rows it
     * already has and the mod keeps running, which is what an err inside the row export does. The
     * message is kept for {@link #lastDefect} so a test can see what a broken export reported.
     */
    @Override
    protected void report(String message) {
        synchronized (defectLock) {
            // The first defect of the frame, because a later row answering correctly must not hide
            // a broken one; the frame is cleared before it asks its first row.
            if (lastDefect == null) {
                lastDefect = message;
            }
        }
        GrugErrors.onRuntimeError(message);
    }

    /**
     * Forgets the defect {@link #lastDefect} answers, before the next frame asks its rows, and when
     * a screen's open time begins: a defect belongs to the open time whose rows reported it.
     */
    public static void clearLastDefect() {
        synchronized (defectLock) {
            lastDefect = null;
        }
    }

    /**
     * The defect the most recent frame's {@code screen_list_row} export reported, or the empty
     * string when none did. A test reads it for a rule the export breaks outside any call of its
     * own, which {@code expect_error} cannot match; see {@code TestTickContext.list_row_error}.
     */
    public static String lastDefect() {
        synchronized (defectLock) {
            return lastDefect == null ? "" : lastDefect;
        }
    }
}
