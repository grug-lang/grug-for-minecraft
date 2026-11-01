package net.grug.minecraft.grug;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Which grug mods of an installed game are on, and the moves that make the game match (#335).
 *
 * <p>A mod is on while its directory sits in {@code grug_mods/}, which every loader and the VM read
 * whole, so turning a mod off moves its directory, files and all, to {@code grug_mods_off/}, and
 * turning it on moves it back or, for a mod bundled in the jar that was never installed, installs
 * it from the jar. Neither changes a file of the mod. What the player wants is the host's own
 * state, {@code grug_mods_state.json} in the game directory, and the game follows it at startup
 * ({@link #apply}): blocks and items register once, with ids, when the game starts, so a change
 * made on the screen takes effect on the next start.
 *
 * <p>A mod the state does not mention is as it is on disk: on when installed, off when only
 * bundled. A game that has never run grug installs {@code examplemod} and turns it on.
 *
 * <p>The state also records, per mod, where an install came from and the SHA-256 of what it
 * installed: the zip's hash in the index for a mod of the catalogue, a hash of the files for a mod
 * bundled in the jar. A mod whose source now holds other bytes is offered as an update (#512). The
 * player starts an update with a button, as no mod updates by itself: it downloads and checks the
 * new copy into {@code grug_mods_updates/}, and the next start replaces the installed directory
 * with it. The state also holds a hash of the files the manager put in place, so the screen can ask
 * before an update replaces a mod whose files the player changed.
 */
public final class GrugModManager {
    /** The host's state, in the game directory beside {@code grug_mods/}. */
    public static final String STATE = "grug_mods_state.json";

    /** Where a mod that is turned off waits, in the game directory. */
    public static final String OFF_DIRECTORY = "grug_mods_off";

    /** Where an update waits, verified, for the next start to put it in place. */
    public static final String UPDATES_DIRECTORY = "grug_mods_updates";

    /** The one bundled mod a game that has never run grug turns on: the tutorial. */
    public static final String TUTORIAL = "examplemod";

    /**
     * Written to {@code grug_mods/} by the first run, which earlier versions used to extract the
     * examples once; its presence still means that this game has run grug.
     */
    static final String MARKER = ".examples_generated.txt";

    /** One mod the screen lists. */
    public static final class Entry {
        private final String name;
        private final boolean bundled;
        private final boolean installed;
        private final long downloadSize;
        private final boolean on;
        private final String prompt;
        private final boolean updatable;
        private final boolean updatePending;
        private final boolean edited;

        Entry(
                String name,
                boolean bundled,
                boolean installed,
                long downloadSize,
                boolean on,
                String prompt,
                boolean updatable,
                boolean updatePending,
                boolean edited) {
            this.name = name;
            this.bundled = bundled;
            this.installed = installed;
            this.downloadSize = downloadSize;
            this.on = on;
            this.prompt = prompt;
            this.updatable = updatable;
            this.updatePending = updatePending;
            this.edited = edited;
        }

        public String name() {
            return name;
        }

        /** Whether the jar holds a copy of the mod. */
        public boolean bundled() {
            return bundled;
        }

        /** Whether the mod's files are on disk, in {@code grug_mods/} or {@code grug_mods_off/}. */
        public boolean installed() {
            return installed;
        }

        /**
         * Whether the catalogue of the latest release holds a copy of the mod, which turning it on
         * downloads when it is neither installed nor bundled in the jar.
         */
        public boolean downloadable() {
            return downloadSize > 0;
        }

        /** The size of the mod's zip in bytes, or 0 when the catalogue does not hold it. */
        public long downloadSize() {
            return downloadSize;
        }

        /** Whether the player wants the mod on, which takes effect at the next start. */
        public boolean on() {
            return on;
        }

        /**
         * The text of the blocking popup to show before turning the mod on, or null when it needs
         * none. Nothing is compiled before the player accepts, since nothing is until the next
         * start.
         */
        public String prompt() {
            return prompt;
        }

        /**
         * Whether the place the mod was installed from now holds another copy of it, which {@link
         * #startUpdate} downloads.
         */
        public boolean updatable() {
            return updatable;
        }

        /**
         * Whether the files of the installed mod are no longer the ones the manager put in place,
         * which an update replaces. Only known for a mod that has an update.
         */
        public boolean edited() {
            return edited;
        }

        /** Whether an update is downloaded and waits for the next start. */
        public boolean updatePending() {
            return updatePending;
        }
    }

    private final File gameDir;
    private final GrugModsExtractor.Resources jar;
    private final Function<String, String> prompts;
    private final GrugCatalogue catalogue;
    private final Executor executor;
    private volatile Map<String, GrugCatalogue.Item> remote = Collections.emptyMap();
    private volatile String catalogueError;
    private volatile boolean loading;
    private boolean loadStarted;

    public GrugModManager(
            File gameDir, GrugModsExtractor.Resources jar, Function<String, String> prompts) {
        this(gameDir, jar, prompts, null);
    }

    /**
     * @param catalogue where mods the jar does not bundle come from, or null for none
     */
    public GrugModManager(
            File gameDir,
            GrugModsExtractor.Resources jar,
            Function<String, String> prompts,
            GrugCatalogue catalogue) {
        this(gameDir, jar, prompts, catalogue, GrugModManager::onNewThread);
    }

    /**
     * @param executor runs the requests, which the game keeps off its own thread
     */
    GrugModManager(
            File gameDir,
            GrugModsExtractor.Resources jar,
            Function<String, String> prompts,
            GrugCatalogue catalogue,
            Executor executor) {
        this.gameDir = gameDir;
        this.jar = jar;
        this.prompts = prompts;
        this.catalogue = catalogue;
        this.executor = executor;
    }

    static void onNewThread(Runnable task) {
        Thread thread = new Thread(task, "grug-mod-manager");
        thread.setDaemon(true);
        thread.start();
    }

    /** No mod asks for a popup before it is turned on. */
    static final Function<String, String> NO_PROMPTS = mod -> null;

    /**
     * The manager of a game directory, over the mods bundled in the jar that loaded grug and the
     * catalogue of the latest release.
     */
    public static GrugModManager of(File gameDir) {
        return new GrugModManager(
                gameDir, GrugModsExtractor.classpath(), NO_PROMPTS, GrugCatalogue.latest());
    }

    private File modsDir() {
        return new File(gameDir, GrugModsDirectory.NAME);
    }

    private File offDir() {
        return new File(gameDir, OFF_DIRECTORY);
    }

    private File updatesDir() {
        return new File(gameDir, UPDATES_DIRECTORY);
    }

    private File stateFile() {
        return new File(gameDir, STATE);
    }

    /**
     * What went wrong reading the catalogue, or null when it was read, is being read or there is
     * none. The list then holds the mods without it.
     */
    public String catalogueError() {
        return catalogueError;
    }

    /** Whether the catalogue is being read, which {@link #list} does not hold the mods of yet. */
    public boolean catalogueLoading() {
        return loading;
    }

    /**
     * Starts reading the catalogue, once, and returns at once: the request runs in the executor.
     */
    public synchronized void loadCatalogue() {
        if (catalogue == null || loadStarted) return;
        loadStarted = true;
        loading = true;
        executor.execute(
                () -> {
                    try {
                        remote = Collections.unmodifiableMap(new TreeMap<>(catalogue.fetch()));
                    } catch (IOException e) {
                        catalogueError = "Could not read the mod catalogue: " + e.getMessage();
                    } finally {
                        loading = false;
                    }
                });
    }

    /** A download of a mod of the catalogue, which runs while the screen shows how far it is. */
    public static final class Install {
        private final String name;
        private final long total;
        private volatile long done;
        private volatile boolean finished;
        private volatile String error;

        Install(String name, long total) {
            this.name = name;
            this.total = total;
        }

        public String name() {
            return name;
        }

        /** The bytes of the zip downloaded so far. */
        public long done() {
            return done;
        }

        /** The size of the zip, as the index states it. */
        public long total() {
            return total;
        }

        /** How far it is, from 0 to 1. */
        public double progress() {
            if (total <= 0) return 1; // only the install of a mod the catalogue lacks, over at once
            return Math.min(1.0, (double) done / total);
        }

        /** Whether it is over, whether it worked or not. */
        public boolean finished() {
            return finished;
        }

        /** Why it failed, once it is over, or null when it worked. */
        public String error() {
            return error;
        }
    }

    /**
     * Starts downloading a mod of the catalogue into {@code grug_mods/} and turning it on, and
     * returns at once. Nothing is installed when the download or its check fails. The mod loads at
     * the next start, as every change does.
     */
    public Install startInstall(String name) {
        GrugCatalogue.Item item = remote.get(name);
        Install install = new Install(name, item == null ? 0 : item.size());
        if (item == null) {
            install.error = name + " is not in the catalogue";
            install.finished = true;
            return install;
        }
        executor.execute(
                () -> {
                    try {
                        Files.createDirectories(modsDir().toPath());
                        catalogue.install(
                                name, item, modsDir().toPath(), bytes -> install.done = bytes);
                        record(name, new Source(Source.RELEASE, item.sha256));
                    } catch (IOException | IllegalStateException e) {
                        // The state file that cannot be written is a fatal of grug's, which
                        // would otherwise end the worker with the download looking like it worked.
                        install.error = e.getMessage();
                    } finally {
                        install.finished = true;
                    }
                });
        return install;
    }

    /** Every mod the game knows of, by name: installed, turned off, bundled or downloadable. */
    public List<Entry> list() {
        Map<String, ModState> state = readState(stateFile());
        TreeSet<String> names = new TreeSet<>(state.keySet());
        names.addAll(directories(modsDir()));
        names.addAll(directories(offDir()));
        Map<String, List<String>> bundled = bundled();
        names.addAll(bundled.keySet());
        Map<String, GrugCatalogue.Item> remote = this.remote;
        names.addAll(remote.keySet());

        List<Entry> entries = new ArrayList<>();
        for (String name : names) {
            boolean running = new File(modsDir(), name).isDirectory();
            boolean installed = running || new File(offDir(), name).isDirectory();
            ModState wanted = state.get(name);
            entries.add(
                    new Entry(
                            name,
                            bundled.containsKey(name),
                            installed,
                            remote.containsKey(name) ? remote.get(name).size() : 0,
                            wanted != null ? wanted.on : running,
                            prompts.apply(name),
                            installed
                                    && wanted != null
                                    && newerSource(name, wanted, bundled) != null,
                            wanted != null && wanted.update != null,
                            installed && wanted != null && edited(name, wanted, bundled, running)));
        }
        return Collections.unmodifiableList(entries);
    }

    /** Records that the player wants a mod on or off. The game follows at its next start. */
    public synchronized void setOn(String name, boolean on) {
        if (!validName(name)) {
            throw Grug.fatal(name + " is not a mod name");
        }
        Map<String, ModState> state = readState(stateFile());
        state.computeIfAbsent(name, key -> new ModState()).on = on;
        writeState(stateFile(), state);
    }

    /**
     * Whether the player accepted unsafe mode for a mod, which the popup path asks before asking
     * again and which the mode itself is decided from (#334).
     *
     * <p>Read from the game's own state and never from the mod's files: the grant is the player's
     * record of a decision about the mod, and an update to the mod replaces the files the decision
     * was about, so it clears the grant.
     */
    public boolean isUnsafeModeGranted(String name) {
        return unsafeModeGrants(gameDir).containsKey(name);
    }

    /**
     * Records that the player accepted unsafe mode for a mod, which the next start reads before
     * anything of the mod compiles.
     */
    public synchronized void grantUnsafeMode(String name) {
        setUnsafeModeGranted(name, true);
    }

    /** Forgets the player's acceptance, so the popup asks again before the mod runs unsafe. */
    public synchronized void revokeUnsafeMode(String name) {
        setUnsafeModeGranted(name, false);
    }

    private synchronized void setUnsafeModeGranted(String name, boolean granted) {
        if (!validName(name)) {
            throw Grug.fatal(name + " is not a mod name");
        }
        Map<String, ModState> state = readState(stateFile());
        ModState mod = state.get(name);
        if (mod == null) {
            // A grant for a mod the state never mentioned makes it an entry, so remember what the
            // game is doing with it rather than let the entry read as a request to turn it off.
            if (!granted) return;
            mod = new ModState();
            mod.on = new File(modsDir(), name).isDirectory();
            state.put(name, mod);
        } else if (mod.unsafeModeGranted == granted) {
            return;
        }
        mod.unsafeModeGranted = granted;
        writeState(stateFile(), state);
    }

    /**
     * Records that a mod was installed from {@code source}, and that the player wants it on. A
     * mod's installed copy is what the record names, so an update is whatever differs from it.
     */
    private synchronized void record(String name, Source source) {
        Map<String, ModState> state = readState(stateFile());
        ModState mod = state.computeIfAbsent(name, key -> new ModState());
        mod.on = true;
        mod.installed = source;
        mod.files = directoryDigest(new File(modsDir(), name));
        mod.update = null;
        // These files are new, so the player has not seen them yet.
        mod.unsafeModeGranted = false;
        writeState(stateFile(), state);
    }

    /**
     * The copy of an installed mod that its source holds now, or null when it is the installed one
     * or the manager cannot tell: a mod installed before the record existed, a mod whose source
     * lacks it now, an update that is already downloaded.
     */
    private Source newerSource(String name, ModState mod, Map<String, List<String>> bundled) {
        if (mod.installed == null || mod.update != null) return null;
        String current = null;
        if (mod.installed.kind.equals(Source.JAR)) {
            List<String> files = bundled.get(name);
            if (files != null) current = jarDigest(name, files);
        } else {
            GrugCatalogue.Item item = remote.get(name);
            if (item != null) current = item.sha256;
        }
        return current == null || current.equals(mod.installed.sha256)
                ? null
                : new Source(mod.installed.kind, current);
    }

    private boolean edited(
            String name, ModState mod, Map<String, List<String>> bundled, boolean running) {
        if (mod.files == null || newerSource(name, mod, bundled) == null) return false;
        File directory = new File(running ? modsDir() : offDir(), name);
        return !mod.files.equals(directoryDigest(directory));
    }

    /** A hash of the paths and bytes of the files of a directory. */
    static String directoryDigest(File directory) {
        StringBuilder listing = new StringBuilder();
        try (Stream<Path> paths = Files.walk(directory.toPath())) {
            List<Path> files =
                    paths.filter(Files::isRegularFile).sorted().collect(Collectors.toList());
            for (Path file : files) {
                listing.append(directory.toPath().relativize(file).toString().replace('\\', '/'))
                        .append('\n')
                        .append(GrugCatalogue.digest("SHA-256", Files.readAllBytes(file)))
                        .append('\n');
            }
        } catch (IOException e) {
            throw Grug.fatal("Failed to read " + directory, e);
        }
        return GrugCatalogue.digest("SHA-256", listing.toString().getBytes(StandardCharsets.UTF_8));
    }

    private final Map<String, String> jarDigests = new ConcurrentHashMap<>();

    /**
     * A hash of the files of a bundled mod: the jar of a release holds other bytes when it moves.
     */
    private String jarDigest(String name, List<String> files) {
        return jarDigests.computeIfAbsent(
                name,
                key -> {
                    StringBuilder listing = new StringBuilder();
                    try {
                        for (String relative : files) {
                            try (InputStream in =
                                    jar.open(GrugModsExtractor.MODS_ROOT + relative)) {
                                if (in == null) {
                                    throw new IOException(relative + " is listed but missing");
                                }
                                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                                byte[] chunk = new byte[8192];
                                for (int n = in.read(chunk); n >= 0; n = in.read(chunk)) {
                                    bytes.write(chunk, 0, n);
                                }
                                listing.append(relative)
                                        .append('\n')
                                        .append(
                                                GrugCatalogue.digest(
                                                        "SHA-256", bytes.toByteArray()))
                                        .append('\n');
                            }
                        }
                    } catch (IOException e) {
                        throw Grug.fatal("Failed to read the mods bundled in the grug jar", e);
                    }
                    return GrugCatalogue.digest(
                            "SHA-256", listing.toString().getBytes(StandardCharsets.UTF_8));
                });
    }

    /**
     * Starts downloading the newer copy of an installed mod into {@code grug_mods_updates/}, and
     * returns at once. Nothing the game runs changes: the next start replaces the installed
     * directory, so a failed download leaves the installed mod as it was.
     */
    public Install startUpdate(String name) {
        ModState mod = readState(stateFile()).get(name);
        boolean installed =
                new File(modsDir(), name).isDirectory() || new File(offDir(), name).isDirectory();
        Source newer = mod == null || !installed ? null : newerSource(name, mod, bundled());
        GrugCatalogue.Item item = remote.get(name);
        boolean download = newer != null && newer.kind.equals(Source.RELEASE);
        Install install = new Install(name, download ? item.size() : 0);
        if (newer == null) {
            install.error = name + " has no update";
            install.finished = true;
            return install;
        }
        executor.execute(
                () -> {
                    try {
                        Path updates = updatesDir().toPath();
                        Files.createDirectories(updates);
                        GrugModsExtractor.deleteTree(updates.resolve(name));
                        if (download) {
                            catalogue.install(name, item, updates, bytes -> install.done = bytes);
                        } else {
                            GrugModsExtractor.install(jar, name, updates);
                        }
                        stage(name, newer);
                    } catch (IOException | IllegalStateException e) {
                        install.error = e.getMessage();
                    } finally {
                        install.finished = true;
                    }
                });
        return install;
    }

    private synchronized void stage(String name, Source newer) {
        Map<String, ModState> state = readState(stateFile());
        state.get(name).update = newer;
        writeState(stateFile(), state);
    }

    /**
     * Makes the game match the state: installs and moves mods into and out of {@code grug_mods/},
     * and on a game that has never run grug installs the tutorial and turns it on. Runs before the
     * VM reads the directory.
     */
    public void apply() {
        Map<String, ModState> state = readState(stateFile());
        File marker = new File(modsDir(), MARKER);
        if (!stateFile().exists() && !marker.exists()) {
            state.computeIfAbsent(TUTORIAL, key -> new ModState()).on = true;
            writeState(stateFile(), state);
        }
        Map<String, List<String>> bundled = bundled();
        boolean changed = false;
        try {
            Files.createDirectories(modsDir().toPath());
            for (Map.Entry<String, ModState> wanted : state.entrySet()) {
                String name = wanted.getKey();
                File running = new File(modsDir(), name);
                File off = new File(offDir(), name);
                if (wanted.getValue().update != null) {
                    replaceWithUpdate(
                            name, wanted.getValue(), running.isDirectory() ? running : off);
                    changed = true;
                }
                if (wanted.getValue().on) {
                    if (running.isDirectory()) continue;
                    if (off.isDirectory()) {
                        move(off, running);
                    } else if (bundled.containsKey(name)) {
                        GrugModsExtractor.install(jar, name, modsDir().toPath());
                        wanted.getValue().installed =
                                new Source(Source.JAR, jarDigest(name, bundled.get(name)));
                        wanted.getValue().files = directoryDigest(running);
                        changed = true;
                    }
                } else if (running.isDirectory()) {
                    if (off.exists()) {
                        throw new IOException(
                                name + " is in both " + modsDir() + " and " + offDir());
                    }
                    Files.createDirectories(offDir().toPath());
                    move(running, off);
                }
            }
            if (changed) writeState(stateFile(), state);
            if (!marker.exists()) {
                Files.write(
                        marker.toPath(),
                        "This game has run grug.\n".getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            throw Grug.fatal("Failed to apply " + stateFile(), e);
        }
    }

    /**
     * Puts the downloaded update in the place of the installed copy. An update whose files or
     * installed copy are gone is dropped.
     */
    private void replaceWithUpdate(String name, ModState mod, File installed) throws IOException {
        File staged = new File(updatesDir(), name);
        if (staged.isDirectory() && installed.isDirectory()) {
            GrugModsExtractor.deleteTree(installed.toPath());
            move(staged, installed);
            mod.installed = mod.update;
            mod.files = directoryDigest(installed);
            // The player accepted unsafe mode for the files that were just replaced, and nobody
            // has verified these, so the popup asks again before they may run unsafe (#334).
            mod.unsafeModeGranted = false;
        } else {
            GrugModsExtractor.deleteTree(staged.toPath());
        }
        mod.update = null;
    }

    private static void move(File from, File to) throws IOException {
        Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE);
    }

    private Map<String, List<String>> bundled() {
        try {
            return GrugModsExtractor.bundled(jar);
        } catch (IOException e) {
            throw Grug.fatal("Failed to read the mods bundled in the grug jar", e);
        }
    }

    private static List<String> directories(File parent) {
        List<String> names = new ArrayList<>();
        File[] children = parent.listFiles(File::isDirectory);
        if (children != null) {
            for (File child : children) {
                // The names a hidden directory starts with are the host's own, such as an install
                // in progress.
                if (!child.getName().startsWith(".")) names.add(child.getName());
            }
        }
        return names;
    }

    /** What the host remembers of one mod. */
    private static final class ModState {
        boolean on;
        Source installed;
        Source update;
        String files;

        /** Whether the player accepted unsafe mode for these files of the mod (#334). */
        boolean unsafeModeGranted;
    }

    /** Where a copy of a mod came from, and the SHA-256 that identifies it there. */
    private static final class Source {
        static final String JAR = "jar";
        static final String RELEASE = "release";

        final String kind;
        final String sha256;

        Source(String kind, String sha256) {
            this.kind = kind;
            this.sha256 = sha256;
        }

        static Source read(JsonElement element) {
            JsonObject object = element.getAsJsonObject();
            String kind = object.get("source").getAsString();
            String sha256 = object.get("sha256").getAsString();
            if (!kind.equals(JAR) && !kind.equals(RELEASE)) {
                throw new JsonParseException(kind + " is not a source");
            }
            return new Source(kind, sha256);
        }

        JsonObject write() {
            JsonObject object = new JsonObject();
            object.addProperty("source", kind);
            object.addProperty("sha256", sha256);
            return object;
        }
    }

    /**
     * Every mod the player granted unsafe mode, by name, read from the game's own state.
     *
     * <p>Per mod and never in the mod's files, because the grant is the player's record of a
     * decision about that mod while its files are what the decision is about (#334).
     */
    public static Map<String, Boolean> unsafeModeGrants(File gameDir) {
        Map<String, Boolean> grants = new TreeMap<>();
        for (Map.Entry<String, ModState> mod : readState(new File(gameDir, STATE)).entrySet()) {
            if (mod.getValue().unsafeModeGranted) grants.put(mod.getKey(), true);
        }
        return Collections.unmodifiableMap(grants);
    }

    private static Map<String, ModState> readState(File stateFile) {
        Map<String, ModState> state = new TreeMap<>();
        if (!stateFile.isFile()) return state;
        JsonObject mods;
        try {
            String text =
                    new String(Files.readAllBytes(stateFile.toPath()), StandardCharsets.UTF_8);
            mods = JsonParser.parseString(text).getAsJsonObject().getAsJsonObject("mods");
            if (mods == null) {
                throw new JsonParseException("no \"mods\" object");
            }
            for (Map.Entry<String, JsonElement> mod : mods.entrySet()) {
                if (!validName(mod.getKey())) {
                    throw new JsonParseException(mod.getKey() + " is not a mod name");
                }
                JsonElement value = mod.getValue();
                ModState parsed = new ModState();
                JsonObject object = value.getAsJsonObject();
                parsed.on = object.get("on").getAsBoolean();
                if (object.has("installed"))
                    parsed.installed = Source.read(object.get("installed"));
                if (object.has("update")) parsed.update = Source.read(object.get("update"));
                if (object.has("files")) parsed.files = object.get("files").getAsString();
                if (object.has("unsafe_mode_granted")) {
                    parsed.unsafeModeGranted = object.get("unsafe_mode_granted").getAsBoolean();
                }
                state.put(mod.getKey(), parsed);
            }
        } catch (IOException
                | JsonParseException
                | IllegalStateException
                | NullPointerException
                | ClassCastException
                | UnsupportedOperationException e) {
            throw Grug.fatal(
                    stateFile + " is not {\"mods\": {\"<name>\": {\"on\": true or false, ...}}}",
                    e);
        }
        return state;
    }

    /**
     * Whether a name is one directory of {@code grug_mods/}, and so cannot lead out of the managed
     * directories when the state, which a player may edit by hand, is applied.
     */
    static boolean validName(String name) {
        return !name.isEmpty()
                && !name.equals(".")
                && !name.equals("..")
                && name.indexOf('/') < 0
                && name.indexOf('\\') < 0
                && name.indexOf(':') < 0
                && name.indexOf('\0') < 0;
    }

    private static void writeState(File stateFile, Map<String, ModState> state) {
        JsonObject mods = new JsonObject();
        for (Map.Entry<String, ModState> entry : state.entrySet()) {
            JsonObject mod = new JsonObject();
            mod.addProperty("on", entry.getValue().on);
            if (entry.getValue().installed != null) {
                mod.add("installed", entry.getValue().installed.write());
            }
            if (entry.getValue().update != null) mod.add("update", entry.getValue().update.write());
            if (entry.getValue().files != null) mod.addProperty("files", entry.getValue().files);
            if (entry.getValue().unsafeModeGranted) {
                mod.addProperty("unsafe_mode_granted", true);
            }
            mods.add(entry.getKey(), mod);
        }
        JsonObject root = new JsonObject();
        root.add("mods", mods);
        Path temporary = stateFile.toPath().resolveSibling(stateFile.getName() + ".tmp");
        try {
            Files.write(temporary, (root + "\n").getBytes(StandardCharsets.UTF_8));
            Files.move(temporary, stateFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw Grug.fatal("Failed to write " + stateFile, e);
        }
    }
}
