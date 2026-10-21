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
 * <p>Loaders show it by {@link #toString}, which is only the message: a class name would push it
 * off screens that show little of it, such as Forge 1.16.5's, which draws two lines. The message
 * puts what to do first for the same reason.
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

    @Override
    public String toString() {
        return getMessage();
    }
}
