package net.grug.minecraft.grug;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * The mode every grug mod's code runs in, decided per mod before any of that mod's code compiles
 * (#334).
 *
 * <p>Unsafe mode skips the checks for host function errors, the time limit and stack overflows, so
 * it gives a mod full access to the process. Java therefore owns the mode: a mod asks for unsafe
 * mode with {@link #REQUEST_KEY} in its {@code about.json}, and the player accepts it with a grant
 * that lives in the game's own state, beside the mods directory's record. Nothing in a mod's files
 * can grant it, and no host function reads or writes it, so a mod can neither grant unsafe mode to
 * itself nor take it from another mod.
 *
 * <p>The rule is the whole of {@link #unsafeMode}: a mod runs unsafe only while it asks to and the
 * player granted it. A mod without the request, or with a grant the manager cleared because an
 * update replaced the files the player accepted, runs safe. Running a correct program safe only
 * makes it slower, because a real defect becomes an err instead of undefined behaviour.
 *
 * <p>An update replaces the files the player accepted, so {@link GrugModManager#revokeUnsafeMode}
 * and the manager's own update path clear the grant, and the popup asks again before the new files
 * may run unsafe.
 *
 * <p>grug-rs has no mode in the pinned revision, so the modes read here are not yet handed to the
 * compiler: every file compiles in the one mode the VM has, which is the safe one. The states are
 * what the compiler call will carry, and what the next phase's popup reads until then. See {@link
 * Grug#compileAllFiles}.
 */
public final class GrugModModes {

    /** The key of a mod's {@code about.json} with which it asks to run in unsafe mode. */
    public static final String REQUEST_KEY = "requests_unsafe_mode";

    private static Map<String, Boolean> requests = Collections.emptyMap();
    private static Map<String, Boolean> grants = Collections.emptyMap();
    private static Map<String, Boolean> unsafeModes = Collections.emptyMap();

    private GrugModModes() {}

    /**
     * Reads every mod's request and the player's grant of it, replacing what an earlier call read.
     *
     * <p>Call this before any mod's code compiles, because a mode belongs to the compilation of a
     * mod's files and not to a call into them.
     *
     * @param gameDirectory the game's directory, where the state that holds the grants lives, or
     *     null for a run that has none, which reads every mod as safe
     */
    public static void load(File modsDirectory, File gameDirectory) {
        Map<String, Boolean> requested = new TreeMap<>();
        Map<String, Boolean> granted =
                gameDirectory == null
                        ? new TreeMap<>()
                        : GrugModManager.unsafeModeGrants(gameDirectory);
        Map<String, Boolean> modes = new TreeMap<>();

        File[] mods = modsDirectory.listFiles(File::isDirectory);
        if (mods != null) {
            for (File mod : mods) {
                String name = mod.getName();
                boolean asks = readRequest(new File(mod, "about.json"));
                boolean accepted = granted.containsKey(name);
                requested.put(name, asks);
                modes.put(name, unsafeMode(asks, accepted));
            }
        }

        requests = Collections.unmodifiableMap(requested);
        grants = granted;
        unsafeModes = Collections.unmodifiableMap(modes);
    }

    /**
     * The one rule: a mod's code runs unsafe only while it asks to and the player granted it.
     *
     * <p>A grant without a request is not a mode: the player marking a mod unsafe by hand is the
     * mod manager's own setting, separate from accepting what a mod asks for.
     */
    public static boolean unsafeMode(boolean requested, boolean granted) {
        return requested && granted;
    }

    /** Whether {@code mod}'s code runs in unsafe mode; a mod nothing was read for runs safe. */
    public static boolean unsafe(String mod) {
        return unsafeModes.getOrDefault(mod, false);
    }

    /** Whether {@code mod} asks to run in unsafe mode, whatever the player answered. */
    public static boolean requestsUnsafeMode(String mod) {
        return requests.getOrDefault(mod, false);
    }

    /** Whether the player accepted unsafe mode for {@code mod}. */
    public static boolean granted(String mod) {
        return grants.containsKey(mod);
    }

    /** Every mod's mode, by name, which is what a compilation of the mods carries. */
    public static Map<String, Boolean> unsafeModes() {
        return unsafeModes;
    }

    /**
     * Whether a mod's {@code about.json} asks for unsafe mode.
     *
     * <p>Anything but a boolean is not a request, not even the string {@code "true"}: {@code
     * about_schema.py} refuses that, and a game that runs a mod nobody validated reads an unclear
     * request as no request, which is the safe side of the answer.
     */
    private static boolean readRequest(File aboutJson) {
        if (!aboutJson.isFile()) {
            return false;
        }
        try {
            // Read whole rather than streaming: an about.json is a few kilobytes, and parsing from
            // a String keeps this to one thing that can fail (the parse).
            String contents =
                    new String(Files.readAllBytes(aboutJson.toPath()), StandardCharsets.UTF_8);
            JsonObject about = JsonParser.parseString(contents).getAsJsonObject();
            JsonElement request = about.get(REQUEST_KEY);
            return request != null
                    && request.isJsonPrimitive()
                    && request.getAsJsonPrimitive().isBoolean()
                    && request.getAsBoolean();
        } catch (Exception e) {
            // A malformed about.json is about_schema.py's to reject, and refusing to start the
            // game over metadata that only asks for a mode would be worse than reading it safe.
            return false;
        }
    }
}
