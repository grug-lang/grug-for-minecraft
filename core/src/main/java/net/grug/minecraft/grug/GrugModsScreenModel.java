package net.grug.minecraft.grug;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;

/**
 * What the mod manager's screen shows and does, apart from how a loader draws it (#335): a page of
 * mods, each a button that turns it on or off, and a blocking popup for a mod that needs one before
 * it is turned on. Every loader's screen renders this and forwards clicks to it.
 */
public final class GrugModsScreenModel {
    private final GrugModManager manager;
    private final int pageSize;
    private List<GrugModManager.Entry> entries;
    private final List<GrugModManager.Entry> atOpen;
    private int page;
    private GrugModManager.Entry asking;
    private String error;

    /**
     * @param pageSize how many mods one page lists, which the loader's screen has room for
     */
    public GrugModsScreenModel(GrugModManager manager, int pageSize) {
        this.manager = manager;
        this.pageSize = pageSize;
        this.entries = manager.list();
        this.atOpen = entries;
    }

    /** The label of the button of a mod on this page: its name and whether it is on. */
    public List<String> labels() {
        List<String> labels = new ArrayList<>();
        for (GrugModManager.Entry entry : pageEntries()) {
            labels.add(
                    entry.name()
                            + ": "
                            + (entry.on() ? "ON" : "OFF")
                            + (needsDownload(entry) ? " (download)" : ""));
        }
        return labels;
    }

    /** Whether turning the mod on downloads it: it is in no place the game can take it from. */
    private static boolean needsDownload(GrugModManager.Entry entry) {
        return entry.downloadable() && !entry.installed() && !entry.bundled();
    }

    private List<GrugModManager.Entry> pageEntries() {
        int from = page * pageSize;
        return entries.subList(from, Math.min(entries.size(), from + pageSize));
    }

    public boolean hasPrevious() {
        return page > 0;
    }

    public boolean hasNext() {
        return (page + 1) * pageSize < entries.size();
    }

    public void previous() {
        if (hasPrevious()) page--;
    }

    public void next() {
        if (hasNext()) page++;
    }

    /** The button of the {@code row}th mod on this page was clicked. */
    public void click(int row) {
        if (asking != null) return; // the popup blocks everything behind it
        GrugModManager.Entry entry = pageEntries().get(row);
        if (!entry.on() && entry.prompt() != null) {
            asking = entry;
            return;
        }
        toggle(entry);
    }

    private void toggle(GrugModManager.Entry entry) {
        error = null;
        if (needsDownload(entry)) {
            try {
                manager.install(entry.name());
            } catch (IOException e) {
                error = "Could not install " + entry.name() + ": " + e.getMessage();
            }
        } else {
            manager.setOn(entry.name(), !entry.on());
        }
        entries = manager.list();
    }

    /**
     * What the screen says went wrong, or null: the last install that failed, or else the catalogue
     * that could not be read.
     */
    public String error() {
        return error != null ? error : manager.catalogueError();
    }

    /** The text of the popup that blocks the screen, or null when there is none. */
    public String popup() {
        return asking == null ? null : asking.name() + ": " + asking.prompt();
    }

    /** The player accepted the popup, which turns the mod on. */
    public void accept() {
        if (asking == null) return;
        GrugModManager.Entry entry = asking;
        asking = null;
        toggle(entry);
    }

    /** The player declined the popup, which leaves the mod off. */
    public void decline() {
        asking = null;
    }

    /** Whether the screen has changed what is on, which only takes effect at the next start. */
    public boolean restartNeeded() {
        Map<String, Boolean> opened = new HashMap<>();
        for (GrugModManager.Entry entry : atOpen) {
            opened.put(entry.name(), entry.on());
        }
        for (GrugModManager.Entry entry : entries) {
            Boolean was = opened.get(entry.name());
            if (was == null || was != entry.on()) return true;
        }
        return false;
    }

    /** The most lines of {@link #errorLines} a screen has room for. */
    static final int ERROR_LINES = 3;

    /**
     * The error, if there is one, broken into at most {@link #ERROR_LINES} lines of at most {@code
     * maxWidth}, which ends in an ellipsis when it had to be cut.
     */
    public List<String> errorLines(int maxWidth, ToIntFunction<String> width) {
        String text = error();
        if (text == null) return new ArrayList<>();
        List<String> lines = wrap(text, maxWidth, width);
        if (lines.size() <= ERROR_LINES) return lines;
        List<String> cut = new ArrayList<>(lines.subList(0, ERROR_LINES));
        cut.set(ERROR_LINES - 1, cut.get(ERROR_LINES - 1) + "...");
        return cut;
    }

    /**
     * Breaks text at spaces into lines no wider than {@code maxWidth}, for the loaders whose fonts
     * have no word wrap of their own. A word wider than a line stands on one.
     */
    public static List<String> wrap(String text, int maxWidth, ToIntFunction<String> width) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String longer = line.length() == 0 ? word : line + " " + word;
            if (line.length() > 0 && width.applyAsInt(longer) > maxWidth) {
                lines.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(longer);
            }
        }
        lines.add(line.toString());
        return lines;
    }
}
