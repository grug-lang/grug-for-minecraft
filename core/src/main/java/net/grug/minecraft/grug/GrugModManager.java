package net.grug.minecraft.grug;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.IOException;
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
import java.util.function.Function;

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
 */
public final class GrugModManager {
    /** The host's state, in the game directory beside {@code grug_mods/}. */
    public static final String STATE = "grug_mods_state.json";

    /** Where a mod that is turned off waits, in the game directory. */
    public static final String OFF_DIRECTORY = "grug_mods_off";

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
        private final boolean on;
        private final String prompt;

        Entry(String name, boolean bundled, boolean installed, boolean on, String prompt) {
            this.name = name;
            this.bundled = bundled;
            this.installed = installed;
            this.on = on;
            this.prompt = prompt;
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
    }

    private final File gameDir;
    private final GrugModsExtractor.Resources jar;
    private final Function<String, String> prompts;

    public GrugModManager(
            File gameDir, GrugModsExtractor.Resources jar, Function<String, String> prompts) {
        this.gameDir = gameDir;
        this.jar = jar;
        this.prompts = prompts;
    }

    /** The manager of a game directory, over the mods bundled in the jar that loaded grug. */
    public static GrugModManager of(File gameDir) {
        return of(gameDir, GrugModsExtractor.classpath());
    }

    /** The manager of a game directory, over the mods bundled in the given jar. */
    static GrugModManager of(File gameDir, GrugModsExtractor.Resources jar) {
        return new GrugModManager(gameDir, jar, mod -> null);
    }

    private File modsDir() {
        return new File(gameDir, GrugModsDirectory.NAME);
    }

    private File offDir() {
        return new File(gameDir, OFF_DIRECTORY);
    }

    private File stateFile() {
        return new File(gameDir, STATE);
    }

    /** Every mod the game knows of, by name: installed, turned off, or only bundled. */
    public List<Entry> list() {
        Map<String, Boolean> state = readState();
        TreeSet<String> names = new TreeSet<>(state.keySet());
        names.addAll(directories(modsDir()));
        names.addAll(directories(offDir()));
        Map<String, List<String>> bundled = bundled();
        names.addAll(bundled.keySet());

        List<Entry> entries = new ArrayList<>();
        for (String name : names) {
            boolean running = new File(modsDir(), name).isDirectory();
            boolean installed = running || new File(offDir(), name).isDirectory();
            Boolean wanted = state.get(name);
            entries.add(
                    new Entry(
                            name,
                            bundled.containsKey(name),
                            installed,
                            wanted != null ? wanted : running,
                            prompts.apply(name)));
        }
        return Collections.unmodifiableList(entries);
    }

    /** Records that the player wants a mod on or off. The game follows at its next start. */
    public void setOn(String name, boolean on) {
        if (!validName(name)) {
            throw Grug.fatal(name + " is not a mod name");
        }
        Map<String, Boolean> state = readState();
        state.put(name, on);
        writeState(state);
    }

    /**
     * Makes the game match the state: installs and moves mods into and out of {@code grug_mods/},
     * and on a game that has never run grug installs the tutorial and turns it on. Runs before the
     * VM reads the directory.
     */
    public void apply() {
        Map<String, Boolean> state = readState();
        File marker = new File(modsDir(), MARKER);
        if (!stateFile().exists() && !marker.exists()) {
            state.put(TUTORIAL, true);
            writeState(state);
        }
        Map<String, List<String>> bundled = bundled();
        try {
            Files.createDirectories(modsDir().toPath());
            for (Map.Entry<String, Boolean> wanted : state.entrySet()) {
                String name = wanted.getKey();
                File running = new File(modsDir(), name);
                File off = new File(offDir(), name);
                if (wanted.getValue()) {
                    if (running.isDirectory()) continue;
                    if (off.isDirectory()) {
                        move(off, running);
                    } else if (bundled.containsKey(name)) {
                        GrugModsExtractor.install(jar, name, modsDir().toPath());
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
            if (!marker.exists()) {
                Files.write(
                        marker.toPath(),
                        "This game has run grug.\n".getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            throw Grug.fatal("Failed to apply " + stateFile(), e);
        }
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

    private Map<String, Boolean> readState() {
        Map<String, Boolean> state = new TreeMap<>();
        if (!stateFile().isFile()) return state;
        JsonObject mods;
        try {
            String text =
                    new String(Files.readAllBytes(stateFile().toPath()), StandardCharsets.UTF_8);
            mods = JsonParser.parseString(text).getAsJsonObject().getAsJsonObject("mods");
            if (mods == null) {
                throw new JsonParseException("no \"mods\" object");
            }
            for (Map.Entry<String, JsonElement> mod : mods.entrySet()) {
                if (!validName(mod.getKey())) {
                    throw new JsonParseException(mod.getKey() + " is not a mod name");
                }
                JsonElement value = mod.getValue();
                if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
                    throw new JsonParseException(mod.getKey() + " is not true or false");
                }
                state.put(mod.getKey(), value.getAsBoolean());
            }
        } catch (IOException | JsonParseException | IllegalStateException e) {
            throw Grug.fatal(stateFile() + " is not {\"mods\": {\"<name>\": true or false}}", e);
        }
        return state;
    }

    /**
     * Whether a name is one directory of {@code grug_mods/}, and so cannot lead out of the managed
     * directories when the state, which a player may edit by hand, is applied.
     */
    private static boolean validName(String name) {
        return !name.isEmpty()
                && !name.equals(".")
                && !name.equals("..")
                && name.indexOf('/') < 0
                && name.indexOf('\\') < 0
                && name.indexOf(':') < 0
                && name.indexOf('\0') < 0;
    }

    private void writeState(Map<String, Boolean> state) {
        JsonObject mods = new JsonObject();
        for (Map.Entry<String, Boolean> mod : state.entrySet()) {
            mods.addProperty(mod.getKey(), mod.getValue());
        }
        JsonObject root = new JsonObject();
        root.add("mods", mods);
        Path temporary = new File(gameDir, STATE + ".tmp").toPath();
        try {
            Files.write(temporary, (root + "\n").getBytes(StandardCharsets.UTF_8));
            Files.move(temporary, stateFile().toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw Grug.fatal("Failed to write " + stateFile(), e);
        }
    }
}
