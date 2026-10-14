package net.grug.minecraft.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.grug.minecraft.grug.GrugTestState;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;

/**
 * Which mods are ports that reproduce their reference exactly, read once at startup from each mod's
 * {@code about.json}.
 *
 * <p>A port reproduces its reference exactly, in behaviour and in graphics, so the first {@code
 * derived_from} entry of its {@code about.json} says everything there is to say: it carries the
 * recreation fields, and its {@code deviations} are the known gaps still to be closed. A port with
 * no {@code deviations} key is complete, and a complete port is compared pixel for pixel. A promise
 * nothing enforces is a comment, so this is the set {@code Screenshot.tolerance} consults: a port
 * with no deviations may not widen a screenshot comparison to accept a pixel that differs. A port
 * that still lists deviations is not claiming to be finished, and a mod that is not a port makes no
 * claim at all.
 *
 * <p>Only the metadata lives here. Which mod owns the running test is test-runner state, so that
 * lives in {@link GrugTestState} next to the rest of it, and this class asks {@link
 * GrugTestState#currentTestIsExactPort()} when it needs an answer rather than reading the runner's
 * state itself.
 */
public final class GrugExactPorts {

    private static final Set<String> exactPorts = new HashSet<>();

    private GrugExactPorts() {}

    /**
     * Replaces the remembered ports with the ones the mods under {@code modsDirectory} declare
     * exact.
     */
    public static void load(File modsDirectory) {
        exactPorts.clear();
        File[] modDirectories = modsDirectory.listFiles(File::isDirectory);
        if (modDirectories == null) {
            return;
        }
        for (File modDirectory : modDirectories) {
            if (readIsExactPort(new File(modDirectory, "about.json"))) {
                exactPorts.add(modDirectory.getName());
            }
        }
    }

    /** Whether {@code modName} is a port with no deviations left from its reference. */
    public static boolean isExactPort(String modName) {
        return exactPorts.contains(modName);
    }

    /**
     * Whether a screenshot assertion may allow {@code percent} of difference per pixel, given the
     * mod that owns the running test.
     *
     * <p>A port with no deviations has to be compared pixel for pixel, because that is the claim it
     * makes and a tolerance would quietly stop the claim being checked. {@code 0} is the
     * pixel-exact comparison every screenshot test defaults to, so it stays allowed, as does any
     * percentage for a port that still has deviations or a mod that is not a port.
     */
    public static boolean allowsTolerance(double percent) {
        return percent <= 0 || !GrugTestState.currentTestIsExactPort();
    }

    /**
     * Whether the first {@code derived_from} entry is a port's recreation with no deviations.
     *
     * <p>Anything unexpected reads as not an exact port rather than as a failure: {@code
     * about_schema.py} is what rejects a malformed {@code about.json}, and refusing to start the
     * game over metadata nothing here needs would be worse than ignoring it.
     */
    private static boolean readIsExactPort(File aboutJson) {
        if (!aboutJson.isFile()) {
            return false;
        }
        try {
            // Read whole rather than streaming: an about.json is a few kilobytes, and parsing from
            // a String keeps this to one thing that can fail (the parse) instead of also having to
            // close a reader on a path where the file may not even be readable.
            String contents =
                    new String(Files.readAllBytes(aboutJson.toPath()), StandardCharsets.UTF_8);
            JsonObject about = JsonParser.parseString(contents).getAsJsonObject();
            if (!about.has("derived_from") || !about.get("derived_from").isJsonArray()) {
                return false;
            }
            JsonArray derivedFrom = about.getAsJsonArray("derived_from");
            if (derivedFrom.isEmpty() || !derivedFrom.get(0).isJsonObject()) {
                return false;
            }
            JsonObject entry = derivedFrom.get(0).getAsJsonObject();
            // The artifacts are what make the entry a port: without any there is nothing to
            // reproduce, which is also what the schema requires of a recreation entry.
            if (!entry.has("artifacts")
                    || !entry.get("artifacts").isJsonArray()
                    || entry.getAsJsonArray("artifacts").isEmpty()) {
                return false;
            }
            // A finished port has no deviations key at all. The schema refuses an empty list, so
            // any key that is present, whatever it holds, means the port is not finished.
            return !entry.has("deviations");
        } catch (Exception e) {
            return false;
        }
    }
}
