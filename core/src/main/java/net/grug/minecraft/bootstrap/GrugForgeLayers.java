package net.grug.minecraft.bootstrap;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.grug.minecraft.grug.Grug;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * The Forge-family layers of the universal jar, and the one the running game starts.
 *
 * <p>Forge and NeoForge read one {@code META-INF/mods.toml} from the jar's root on every version,
 * so a range in it would stop every other version's launch (#493). The root metadata declares no
 * range, and this class makes the choice instead, from {@code grug_forge_layers.json}: a layer runs
 * only on the Minecraft version it targets, and on every other game nothing of it is loaded.
 * universal_jar.py reads the same table to check the jar and to model which layer each game starts.
 *
 * <p>Everything here is Java 8 and touches no class of any game: Forge 1.13 to 1.16 run on Java 8,
 * and this runs on every Forge-family game a player puts the jar in.
 */
public final class GrugForgeLayers {
    /** The table, at the root of the universal jar and of core's own jar. */
    static final String TABLE = "grug_forge_layers.json";

    /** Forge's loader class, which reports the game it launched from Forge 1.17 on. */
    static final String FML_LOADER = "net.minecraftforge.fml.loading.FMLLoader";

    /** Opens a resource of the grug jar by its name, or answers null for a missing one. */
    interface Resources {
        InputStream open(String name);
    }

    /**
     * The grug jar's resources, through the class loader rather than this class: in a development
     * run core is a module of its own beside the layer's, and a class only finds resources of its
     * own module, while its loader finds a root resource in any module it defines.
     */
    static final Resources JAR = GrugForgeLayers.class.getClassLoader()::getResourceAsStream;

    /** One Forge-family layer: the game it targets, the class that starts it, its mixin config. */
    public static final class Layer {
        public final String loader;
        public final String minecraft;
        public final String entrypoint;
        public final String mixins;

        Layer(String loader, String minecraft, String entrypoint, String mixins) {
            this.loader = loader;
            this.minecraft = minecraft;
            this.entrypoint = entrypoint;
            this.mixins = mixins;
        }
    }

    /** The game Forge reports, or null for a game no Forge layer can target. */
    static final class Game {
        final String minecraft;
        final boolean client;

        Game(String minecraft, boolean client) {
            this.minecraft = minecraft;
            this.client = client;
        }
    }

    private final List<Layer> layers;
    private final Game game;

    GrugForgeLayers(List<Layer> layers, Game game) {
        this.layers = layers;
        this.game = game;
    }

    /** The table in this jar, against the game Forge launched. */
    public static GrugForgeLayers load() {
        return new GrugForgeLayers(read(JAR, TABLE), game(FML_LOADER));
    }

    static List<Layer> read(Resources jar, String table) {
        List<Layer> layers = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry : readJson(jar, table).entrySet()) {
            JsonObject layer = entry.getValue().getAsJsonObject();
            layers.add(
                    new Layer(
                            entry.getKey(),
                            layer.get("minecraft").getAsString(),
                            layer.get("entrypoint").getAsString(),
                            layer.get("mixins").getAsString()));
        }
        return Collections.unmodifiableList(layers);
    }

    /** A JSON object the grug jar holds, which has to be there: the build puts it in. */
    static JsonObject readJson(Resources jar, String name) {
        try (InputStream in = jar.open(name)) {
            if (in == null) {
                throw Grug.fatal(name + " is missing from the grug jar");
            }
            Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8);
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (IOException e) {
            throw Grug.fatal("Failed to read " + name, e);
        }
    }

    /**
     * The game Forge launched, read from its loader class, or null when the class or its {@code
     * versionInfo} is absent: NeoForge from 20.2 on, Forge before 1.17, and every other loader,
     * none of which a Forge layer targets.
     */
    static Game game(String fmlLoader) {
        Class<?> loader;
        Object versionInfo;
        try {
            loader = Class.forName(fmlLoader);
            versionInfo = loader.getMethod("versionInfo").invoke(null);
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            return null;
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw Grug.fatal("Forge's " + fmlLoader + ".versionInfo() failed", e);
        }
        try {
            String minecraft =
                    (String) versionInfo.getClass().getMethod("mcVersion").invoke(versionInfo);
            Object dist = loader.getMethod("getDist").invoke(null);
            return new Game(minecraft, "CLIENT".equals(((Enum<?>) dist).name()));
        } catch (ReflectiveOperationException e) {
            throw Grug.fatal("Forge's " + fmlLoader + " reports no Minecraft version or side", e);
        }
    }

    /** The layer the running game starts, or null when it starts none. */
    public Layer running() {
        if (game == null) {
            return null;
        }
        Layer running = null;
        for (Layer layer : layers) {
            if (layer.minecraft.equals(game.minecraft)) {
                if (running != null) {
                    throw Grug.fatal(
                            running.loader
                                    + " and "
                                    + layer.loader
                                    + " both target Minecraft "
                                    + game.minecraft);
                }
                running = layer;
            }
        }
        return running;
    }

    /**
     * Whether the game is a client, which only means anything while {@link #running} is not null.
     */
    boolean client() {
        return game.client;
    }

    /**
     * Constructs the running layer's entrypoint, which registers everything grug adds to the game.
     * On a game that starts no layer it reports that through the loader, which shows it as this
     * mod's loading error (#493).
     */
    public void start(ClassLoader classLoader) {
        Layer layer = running();
        if (layer == null) {
            throw GrugUnsupportedGame.error(
                    game == null ? "this game" : "Minecraft " + game.minecraft + " on this loader");
        }
        try {
            Class.forName(layer.entrypoint, true, classLoader)
                    .getDeclaredConstructor()
                    .newInstance();
        } catch (InvocationTargetException e) {
            // The layer's own failure, which Forge reports as the mod's: unwrapped, so its message
            // names what went wrong rather than the reflection around it.
            Throwable cause = e.getCause();
            throw cause instanceof RuntimeException
                    ? (RuntimeException) cause
                    : Grug.fatal("Starting " + layer.loader + " failed", cause);
        } catch (ReflectiveOperationException e) {
            throw Grug.fatal("Cannot construct " + layer.entrypoint + " of " + layer.loader, e);
        }
    }
}
