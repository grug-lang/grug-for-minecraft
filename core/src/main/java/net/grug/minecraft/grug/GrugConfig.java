package net.grug.minecraft.grug;

import net.grug.minecraft.core.GrugCore;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * A mod's configuration: the options its {@code about.json} declares, read from a {@code key:
 * value} file the host owns.
 *
 * <p>The reference mods of this repository keep their options in a flat YAML file the game's config
 * library writes and reads, so the host keeps the same shape: one {@code key: value} line per
 * option, a comment line starting with {@code #}, and blank lines ignored. The file is written with
 * every declared option and its default the first time the mod reads its configuration, which is
 * what gives a player a file to edit. A value the file cannot supply (missing, of the wrong type,
 * outside the declared range, or not a colour) is answered as the declared default rather than
 * refusing the read, because a typo in a file a player edited must not break the mod that owns it;
 * {@code about_schema.py} keeps the declarations themselves honest.
 *
 * <p>The values are cached per mod and re-read when the file's modification time changes, so an
 * edit made outside the game applies on the next read without a restart.
 */
public final class GrugConfig {
    /** The directory under the game directory the host keeps mod configuration files in. */
    public static final String DIRECTORY = "grug";

    /** The one mod's values, resolved against its declarations. */
    public static final class Values {
        private final String mod;
        private final GrugMetadata.Mod metadata;
        private final Map<String, String> file;

        Values(String mod, GrugMetadata.Mod metadata, Map<String, String> file) {
            this.mod = mod;
            this.metadata = metadata;
            this.file = file;
        }

        /** Every declared option, in declaration order, which is the order a file is written in. */
        public List<GrugMetadata.Option> options() {
            return metadata.options();
        }

        /** Whether the mod declares the option, which a mistyped key does not. */
        public boolean declares(String key) {
            return metadata.option(key) != null;
        }

        /** The option a key names, or null when the mod declares no such option. */
        public GrugMetadata.Option option(String key) {
            return metadata.option(key);
        }

        /**
         * The boolean option, or the declared default when the file does not hold a useable one.
         */
        public boolean bool(String key) {
            GrugMetadata.Option option = metadata.option(key);
            if (option == null) {
                reportMissing(key);
                return false;
            }
            if (!"boolean".equals(option.type())) return option.defaultBool();
            String raw = file.get(key);
            if (raw != null) {
                String value = raw.trim();
                if (value.equalsIgnoreCase("true")) return true;
                if (value.equalsIgnoreCase("false")) return false;
            }
            return option.defaultBool();
        }

        /** The number option, or the declared default when the file's value is unusable. */
        public double number(String key) {
            GrugMetadata.Option option = metadata.option(key);
            if (option == null) {
                reportMissing(key);
                return 0.0;
            }
            if (!"number".equals(option.type())) return option.defaultNumber();
            String raw = file.get(key);
            if (raw != null) {
                try {
                    double value = Double.parseDouble(raw.trim());
                    if (!Double.isNaN(value)
                            && !Double.isInfinite(value)
                            && option.inRange(value)) {
                        return value;
                    }
                } catch (NumberFormatException e) {
                    // A value no number parser reads is the default, like one out of range.
                }
            }
            return option.defaultNumber();
        }

        /**
         * The colour option as 0xAARRGGBB, or the declared default when the file's value is bad.
         */
        public int color(String key) {
            GrugMetadata.Option option = metadata.option(key);
            if (option == null) {
                reportMissing(key);
                return GrugMetadata.FALLBACK_COLOR;
            }
            if (!"color".equals(option.type())) return option.defaultColor();
            String raw = file.get(key);
            if (raw != null) {
                int value = GrugMetadata.colorCode(raw);
                if (value != GrugMetadata.INVALID_COLOR) return value;
            }
            return option.defaultColor();
        }

        /** Reports a key the mod does not declare, once, so a typo names itself. */
        private void reportMissing(String key) {
            report("GrugConfig: the mod " + mod + " declares no option named " + key + ".");
        }
    }

    /** One mod's values and the file's modification time they were read from, or 0 when absent. */
    private static final class Cached {
        final Values values;
        final long modified;
        final boolean exists;

        Cached(Values values, long modified, boolean exists) {
            this.values = values;
            this.modified = modified;
            this.exists = exists;
        }
    }

    private static final Map<String, Cached> cache = new HashMap<>();

    /** The problems already reported, so a value read every frame reports its cause once. */
    private static final Set<String> reported = new HashSet<>();

    /** Where a problem is reported, which a unit test replaces to read it. */
    static Consumer<String> reporter =
            problem -> Grug.hostFunctionErrorHappened(Grug.statePtr, problem);

    @GrugGenerated("utility class: never instantiated")
    private GrugConfig() {}

    /** The file a mod's configuration is kept in. */
    public static File file(File gameDir, String mod) {
        return new File(new File(new File(gameDir, "config"), DIRECTORY), mod + ".yml");
    }

    /**
     * The values of one mod, from the directories the running game uses, cached until the file
     * changes.
     */
    public static Values forMod(String mod) {
        File gameDir = GrugCore.getAdapter().getGameDirectory();
        File modsDir = GrugCore.getAdapter().getGrugModsDirectory();
        File config = file(gameDir, mod);
        String key = modsDir.getAbsolutePath() + '/' + mod;
        long modified = config.lastModified();
        boolean exists = config.isFile();
        Cached cached = cache.get(key);
        if (cached != null && cached.modified == modified && cached.exists == exists) {
            return cached.values;
        }
        GrugMetadata.Mod metadata = GrugMetadata.forMod(modsDir, mod);
        Values values;
        try {
            if (!exists) {
                writeDefaults(config, metadata);
            }
            values = new Values(mod, metadata, parse(config));
        } catch (IOException | RuntimeException e) {
            report(
                    "GrugConfig: the configuration of "
                            + mod
                            + " at "
                            + config
                            + " cannot be read ("
                            + e
                            + "), so every option answers its declared default.");
            values = new Values(mod, metadata, new HashMap<String, String>());
        }
        cache.put(key, new Cached(values, config.lastModified(), config.isFile()));
        return values;
    }

    /**
     * Writes a mod's configuration file with its declared defaults when it has none, so a player
     * has a file to edit. An existing file is never touched.
     */
    static void writeDefaults(File config, GrugMetadata.Mod metadata) throws IOException {
        if (metadata.options().isEmpty()) return;
        File parent = config.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("cannot create " + parent);
        }
        StringBuilder text = new StringBuilder();
        text.append(
                        "# Every option the mod declares, with its default. A value edited here is"
                                + " read")
                .append(" again the next time the game reads it.\n");
        for (GrugMetadata.Option option : metadata.options()) {
            if (option.description().isEmpty()) {
                text.append("# ").append(option.title()).append('\n');
            } else {
                text.append("# ")
                        .append(option.title())
                        .append(": ")
                        .append(option.description())
                        .append('\n');
            }
            text.append(option.key()).append(": ").append(option.defaultTextLine()).append('\n');
        }
        Files.write(config.toPath(), text.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** Reads one {@code key: value} file, keeping the text of every value it holds. */
    static Map<String, String> parse(File config) throws IOException {
        Map<String, String> values = new HashMap<>();
        if (!config.isFile()) return values;
        List<String> lines =
                new ArrayList<>(Files.readAllLines(config.toPath(), StandardCharsets.UTF_8));
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            int colon = trimmed.indexOf(':');
            if (colon < 1) continue;
            String key = trimmed.substring(0, colon).trim();
            String value = trimmed.substring(colon + 1).trim();
            values.put(key, value);
        }
        return values;
    }

    private static void report(String problem) {
        if (reported.add(problem)) {
            reporter.accept(problem);
        }
    }
}
