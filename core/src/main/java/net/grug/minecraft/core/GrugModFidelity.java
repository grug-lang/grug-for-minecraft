package net.grug.minecraft.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.grug.minecraft.grug.Grug;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code recreation.fidelity} every mod declares in its {@code about.json}, read once at
 * startup.
 *
 * <p>{@code exact} promises the reference is reproduced pixel for pixel. A promise nothing enforces
 * is a comment, so this is the map {@code Screenshot.tolerance} consults: a mod that claims to be
 * exact may not widen a screenshot comparison to accept a pixel that differs. The other two values
 * promise nothing about pixels, so they say nothing here.
 *
 * <p>Only the metadata lives here. Which mod owns the running test is test-runner state, so that
 * lives in {@link Grug} next to the rest of it, and this class asks {@link
 * Grug#currentTestIsExact()} when it needs an answer rather than reading the runner's state itself.
 */
public final class GrugModFidelity {

    /** The only fidelity that promises a pixel-for-pixel reproduction of the reference. */
    public static final String EXACT = "exact";

    /**
     * Every fidelity {@code about.json} accepts, so a test-only override can be checked against it.
     */
    public static final List<String> FIDELITIES =
            Collections.unmodifiableList(Arrays.asList(EXACT, "behavioral", "inspired"));

    private static final Map<String, String> fidelityByMod = new HashMap<>();

    private GrugModFidelity() {}

    /**
     * Replaces the remembered fidelities with the ones the mods under {@code modsDirectory}
     * declare.
     */
    public static void load(File modsDirectory) {
        fidelityByMod.clear();
        File[] modDirectories = modsDirectory.listFiles(File::isDirectory);
        if (modDirectories == null) {
            return;
        }
        for (File modDirectory : modDirectories) {
            String fidelity = readFidelity(new File(modDirectory, "about.json"));
            if (fidelity != null) {
                fidelityByMod.put(modDirectory.getName(), fidelity);
            }
        }
    }

    /** The fidelity {@code modName} declares, or null when it declares none. */
    public static String of(String modName) {
        return fidelityByMod.get(modName);
    }

    /** Whether {@code modName} promises a pixel-exact recreation of its reference. */
    public static boolean isExact(String modName) {
        return EXACT.equals(of(modName));
    }

    /**
     * Whether a screenshot assertion may allow {@code percent} of difference per pixel, given the
     * fidelity of the mod that owns the running test.
     *
     * <p>An exact recreation has to be compared pixel for pixel, because that is the claim its
     * label makes and a tolerance would quietly stop the claim being checked. {@code 0} is the
     * pixel-exact comparison every screenshot test defaults to, so it stays allowed, as does any
     * percentage for a mod that promises less than exact or makes no promise at all.
     */
    public static boolean allowsTolerance(double percent) {
        return percent <= 0 || !Grug.currentTestIsExact();
    }

    /**
     * The {@code recreation.fidelity} in {@code aboutJson}, or null when there is none to read.
     *
     * <p>Anything unexpected reads as no fidelity rather than as a failure: {@code about_schema.py}
     * is what rejects a malformed {@code about.json}, and refusing to start the game over metadata
     * nothing here needs would be worse than ignoring it.
     */
    private static String readFidelity(File aboutJson) {
        if (!aboutJson.isFile()) {
            return null;
        }
        try {
            // Read whole rather than streaming: an about.json is a few kilobytes, and parsing from
            // a String keeps this to one thing that can fail (the parse) instead of also having to
            // close a reader on a path where the file may not even be readable.
            String contents =
                    new String(Files.readAllBytes(aboutJson.toPath()), StandardCharsets.UTF_8);
            JsonObject about = JsonParser.parseString(contents).getAsJsonObject();
            if (!about.has("recreation") || !about.get("recreation").isJsonObject()) {
                return null;
            }
            JsonObject recreation = about.getAsJsonObject("recreation");
            if (!recreation.has("fidelity") || !recreation.get("fidelity").isJsonPrimitive()) {
                return null;
            }
            return recreation.get("fidelity").getAsString();
        } catch (Exception e) {
            return null;
        }
    }
}
