package net.grug.minecraft.grug;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
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
    private final Map<String, Boolean> atOpen = new HashMap<>();
    private int page;
    private GrugModManager.Entry asking;
    private final List<String> confirming = new ArrayList<>();
    private String error;
    private GrugModManager.Install installing;
    private boolean updating;
    private final Deque<String> queued = new ArrayDeque<>();
    private boolean catalogueWasLoading;

    /**
     * @param pageSize how many mods one page lists, which the loader's screen has room for
     */
    public GrugModsScreenModel(GrugModManager manager, int pageSize) {
        this.manager = manager;
        this.pageSize = pageSize;
        manager.loadCatalogue();
        this.catalogueWasLoading = manager.catalogueLoading();
        refresh();
    }

    /**
     * Takes the manager's list, and remembers what each mod it has not listed before was. The mod
     * that needs the most ids of the first kind that is short comes first, then the most of the
     * next kind, then by name, so a player short of ids sees the mods worth turning off at the top,
     * and the order does not move as they are turned off. The kinds are never added together: ids
     * of one kind do not stand in for another, so a mod of one block and a hundred items does not
     * outrank a mod of two blocks when blocks are what is short.
     */
    private void refresh() {
        entries = new ArrayList<>(manager.list());
        entries.sort(
                (a, b) -> {
                    for (GrugIdShortage shortage : GrugIdShortage.all()) {
                        int byNeed =
                                Integer.compare(shortage.needs(b.name()), shortage.needs(a.name()));
                        if (byNeed != 0) return byNeed;
                    }
                    return a.name().compareTo(b.name());
                });
        for (GrugModManager.Entry entry : entries) {
            atOpen.putIfAbsent(entry.name(), entry.on());
        }
    }

    /**
     * Takes in what the requests in the background finished since the last call, and answers
     * whether the page changed, so the loader's screen rebuilds its buttons. A screen calls it
     * every frame.
     */
    public boolean poll() {
        boolean changed = false;
        boolean loading = manager.catalogueLoading();
        if (catalogueWasLoading && !loading) {
            catalogueWasLoading = false;
            refresh();
            changed = true;
        }
        if (installing != null && installing.finished()) {
            error =
                    installing.error() == null
                            ? null
                            : "Could not "
                                    + (updating ? "update " : "install ")
                                    + installing.name()
                                    + ": "
                                    + installing.error();
            installing = null;
            if (error != null) queued.clear();
            refresh();
            if (!queued.isEmpty()) installing = manager.startUpdate(queued.poll());
            changed = true;
        }
        return changed;
    }

    /** Whether a download is running, which keeps every button of the page from answering. */
    public boolean busy() {
        return installing != null;
    }

    /** How far the download is, from 0 to 1, or -1 when none is running. */
    public double progress() {
        return installing == null ? -1 : installing.progress();
    }

    /** What the screen is waiting for, or null: the catalogue, or the download that is running. */
    public String status() {
        if (installing != null) {
            return (updating ? "Updating " : "Downloading ")
                    + installing.name()
                    + ": "
                    + formatSize(installing.done())
                    + " of "
                    + formatSize(installing.total());
        }
        return manager.catalogueLoading() ? "Loading the mod catalogue..." : null;
    }

    /** A size in bytes the way a screen shows it: B, KiB, MiB or GiB, to one decimal. */
    public static String formatSize(long bytes) {
        String[] units = {"B", "KiB", "MiB", "GiB"};
        double value = bytes;
        int unit = 0;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return unit == 0
                ? bytes + " B"
                : String.format(java.util.Locale.ROOT, "%.1f %s", value, units[unit]);
    }

    /**
     * The label of the button of a mod on this page: its name and whether it is on, then what it
     * costs: the ids it needs while the game is short of them, or the download it would be.
     */
    public List<String> labels() {
        List<String> labels = new ArrayList<>();
        for (GrugModManager.Entry entry : pageEntries()) {
            String cost = idNeeds(entry.name());
            if (needsDownload(entry)) cost = "download, " + formatSize(entry.downloadSize());
            labels.add(
                    entry.name()
                            + ": "
                            + (entry.on() ? "ON" : "OFF")
                            + (cost.isEmpty() ? "" : " (" + cost + ")"));
        }
        return labels;
    }

    /** What a mod needs of the kinds of ids that are short, like "32 blocks, 1 item". */
    private static String idNeeds(String mod) {
        StringBuilder needs = new StringBuilder();
        for (GrugIdShortage shortage : GrugIdShortage.all()) {
            int count = shortage.needs(mod);
            if (count == 0) continue;
            if (needs.length() > 0) needs.append(", ");
            needs.append(count).append(' ').append(shortage.kind()).append(count == 1 ? "" : "s");
        }
        return needs.toString();
    }

    /** Whether the game ran short of ids, which makes this screen the way past the title screen. */
    public boolean shortOfIds() {
        return !GrugIdShortage.all().isEmpty();
    }

    /**
     * What the screen says about the ids, a line for each kind that is short: how far short it is,
     * or that turning off what the player has will be enough once the game restarts.
     */
    public List<String> idLines() {
        List<String> lines = new ArrayList<>();
        for (GrugIdShortage shortage : GrugIdShortage.all()) {
            int stillMissing = shortage.missing() - freed(shortage);
            String kind =
                    Character.toUpperCase(shortage.kind().charAt(0)) + shortage.kind().substring(1);
            lines.add(
                    stillMissing <= 0
                            ? kind + " ids: enough once the game restarts."
                            : kind
                                    + " ids: "
                                    + stillMissing
                                    + " short ("
                                    + shortage.needed()
                                    + " needed, "
                                    + shortage.free()
                                    + " free). Turn off mods to free them.");
        }
        return lines;
    }

    /** Whether turning off what the player has makes every kind of id fit. */
    public boolean idsFit() {
        for (GrugIdShortage shortage : GrugIdShortage.all()) {
            if (shortage.missing() - freed(shortage) > 0) return false;
        }
        return true;
    }

    /** The ids of the kind that the mods turned off since the screen opened would give back. */
    private int freed(GrugIdShortage shortage) {
        int freed = 0;
        for (GrugModManager.Entry entry : entries) {
            if (Boolean.TRUE.equals(atOpen.get(entry.name())) && !entry.on()) {
                freed += shortage.needs(entry.name());
            }
        }
        return freed;
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

    /** Whether each mod of this page has an update to download, which its update button shows. */
    public List<Boolean> updates() {
        List<Boolean> updates = new ArrayList<>();
        for (GrugModManager.Entry entry : pageEntries()) {
            updates.add(entry.updatable());
        }
        return updates;
    }

    private int updatableCount() {
        int count = 0;
        for (GrugModManager.Entry entry : entries) {
            if (entry.updatable()) count++;
        }
        return count;
    }

    /** The label of the button that updates every mod, or null when no mod has an update. */
    public String updateAllLabel() {
        int count = updatableCount();
        return count == 0 ? null : "Update all (" + count + ")";
    }

    /** The update button of the {@code row}th mod on this page was clicked. */
    public void clickUpdate(int row) {
        if (blocked()) return;
        GrugModManager.Entry entry = pageEntries().get(row);
        startUpdates(Collections.singletonList(entry));
    }

    /** The button that updates every mod was clicked: the downloads run one after another. */
    public void clickUpdateAll() {
        if (blocked()) return;
        List<GrugModManager.Entry> all = new ArrayList<>();
        for (GrugModManager.Entry entry : entries) {
            if (entry.updatable()) all.add(entry);
        }
        startUpdates(all);
    }

    /** Asks first when an update replaces files the player changed, since it keeps no copy. */
    private void startUpdates(List<GrugModManager.Entry> updates) {
        error = null;
        for (GrugModManager.Entry entry : updates) {
            queued.add(entry.name());
            if (entry.edited()) confirming.add(entry.name());
        }
        if (confirming.isEmpty()) startQueued();
    }

    private void startQueued() {
        updating = true;
        if (!queued.isEmpty()) installing = manager.startUpdate(queued.poll());
    }

    /** The button of the {@code row}th mod on this page was clicked. */
    public void click(int row) {
        if (blocked()) return; // a popup or a download blocks the rest
        GrugModManager.Entry entry = pageEntries().get(row);
        // A mod that was off when the screen opened is in no shortage count, so turning it on would
        // spend ids the screen has promised to be free.
        if (shortOfIds() && !entry.on() && !Boolean.TRUE.equals(atOpen.get(entry.name()))) {
            error =
                    "The game is short of ids, so "
                            + entry.name()
                            + " stays off. Turn off a mod first.";
            return;
        }
        if (!entry.on() && entry.prompt() != null) {
            asking = entry;
            return;
        }
        toggle(entry);
    }

    private void toggle(GrugModManager.Entry entry) {
        error = null;
        if (needsDownload(entry)) {
            updating = false;
            installing = manager.startInstall(entry.name());
            return;
        }
        manager.setOn(entry.name(), !entry.on());
        refresh();
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
        if (!confirming.isEmpty()) {
            return String.join(", ", confirming)
                    + (confirming.size() == 1 ? " has" : " have")
                    + " files you changed, which the update replaces. Update anyway?";
        }
        return asking == null ? null : asking.name() + ": " + asking.prompt();
    }

    /** The player accepted the popup, which turns the mod on. */
    public void accept() {
        if (!confirming.isEmpty()) {
            confirming.clear();
            startQueued();
            return;
        }
        if (asking == null) return;
        GrugModManager.Entry entry = asking;
        asking = null;
        toggle(entry);
    }

    /** The player declined the popup, which leaves the mod off. */
    public void decline() {
        confirming.clear();
        queued.clear();
        asking = null;
    }

    private boolean blocked() {
        return asking != null || !confirming.isEmpty() || installing != null;
    }

    /** Whether the screen has changed what is on, which only takes effect at the next start. */
    public boolean restartNeeded() {
        for (GrugModManager.Entry entry : entries) {
            if (entry.updatePending() || atOpen.get(entry.name()) != entry.on()) return true;
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
     * have no word wrap of their own. A word wider than a line is cut into pieces that each fit.
     */
    public static List<String> wrap(String text, int maxWidth, ToIntFunction<String> width) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String longer = line.length() == 0 ? word : line + " " + word;
            if (line.length() > 0 && width.applyAsInt(longer) > maxWidth) {
                lines.add(line.toString());
                line = new StringBuilder();
            }
            // What is left of the word goes on the line it can start, cut where the line is full.
            String rest = line.length() == 0 ? word : line + " " + word;
            line = new StringBuilder();
            while (width.applyAsInt(rest) > maxWidth && rest.length() > 1) {
                int cut = rest.length() - 1;
                while (cut > 1 && width.applyAsInt(rest.substring(0, cut)) > maxWidth) cut--;
                lines.add(rest.substring(0, cut));
                rest = rest.substring(cut);
            }
            line.append(rest);
        }
        lines.add(line.toString());
        return lines;
    }
}
