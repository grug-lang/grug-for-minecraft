package net.grug.minecraft.bootstrap;

import net.grug.minecraft.grug.Grug;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The error grug reports on a game the universal jar has no layer for (#493): the same kind of
 * error a mod installed on the wrong version reports, through its mod loader's own error path, so
 * the player learns at once that grug does not run there, what to do, and which games it runs on.
 *
 * <p>Its message puts what to do first, since some loaders show little of it: Forge 1.16.5 gives an
 * error one line of its screen, and Forge and NeoForge put this class's full name before the
 * message, which is why the name says what happened.
 */
public final class GrugUnsupportedGameException extends RuntimeException {
    /** The games the jar has a layer for, one per line, which universal_jar.py writes. */
    static final String LIST = "grug_supported_games.txt";

    GrugUnsupportedGameException(String message) {
        super(message);
    }

    /** The error for a game, as the loader that reports it can name it. */
    public static GrugUnsupportedGameException of(String game) {
        return of(game, GrugForgeLayers.JAR);
    }

    static GrugUnsupportedGameException of(String game, GrugForgeLayers.Resources jar) {
        return new GrugUnsupportedGameException(
                "grug does not run on "
                        + game
                        + ": remove it from the mods folder. It runs on: "
                        + String.join(", ", supported(jar))
                        + ".");
    }

    static List<String> supported(GrugForgeLayers.Resources jar) {
        try (InputStream in = jar.open(LIST)) {
            if (in == null) {
                throw Grug.fatal(LIST + " is missing from the grug jar");
            }
            BufferedReader reader =
                    new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            List<String> games = new ArrayList<>();
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                if (!line.isEmpty()) {
                    games.add(line);
                }
            }
            return games;
        } catch (IOException e) {
            throw Grug.fatal("Failed to read " + LIST, e);
        }
    }
}
