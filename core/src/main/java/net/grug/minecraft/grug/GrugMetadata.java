package net.grug.minecraft.grug;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.grug.minecraft.core.GrugCore;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The parts of a mod's {@code about.json} the host acts on: the key bindings it declares and the
 * configuration options it declares.
 *
 * <p>Both are declarations rather than code, so the host registers a binding with the game's
 * Controls screen and writes a configuration file without the mod calling anything: a mod that
 * declares nothing pays nothing, and a mod's binding is in the Controls screen before the player
 * opens a world. {@code about_schema.py} is what rejects a malformed declaration, so a declaration
 * this class cannot read is read as none rather than stopping the game, the way the mod's unsafe
 * mode request is.
 *
 * <p>The parsed metadata is cached per {@code about.json} and re-read when the file's modification
 * time changes, so the hot reload that rewrites a mod's {@code about.json} is picked up without a
 * restart.
 */
public final class GrugMetadata {
    /** One declared key binding: the game's own key with a name the player can rebind. */
    public static final class Key {
        private final String id;
        private final String name;
        private final int code;

        Key(String id, String name, int code) {
            this.id = id;
            this.name = name;
            this.code = code;
        }

        /** The mod's own name for the binding, such as {@code toggle}. */
        public String id() {
            return id;
        }

        /** The name the mod declares for the binding, which is the reference's key name as text. */
        public String name() {
            return name;
        }

        /**
         * The default key's upper case character code, which is what a loader that cannot register
         * a binding polls instead, or -1 when the declaration's key is not a letter or a digit.
         */
        public int code() {
            return code;
        }
    }

    /**
     * One declared binding with the mod that declared it, which is what a loader registers with the
     * game's Controls screen and polls.
     */
    public static final class DeclaredKey implements Comparable<DeclaredKey> {
        private final String mod;
        private final Key key;
        private String label;

        DeclaredKey(String mod, Key key) {
            this.mod = mod;
            this.key = key;
            this.label = key.name();
        }

        public String mod() {
            return mod;
        }

        public Key key() {
            return key;
        }

        /**
         * The name the Controls screen shows, which is the mod's own unless another declared
         * binding already uses it. The loaders without a separate label identify a binding by this
         * one string, so a repeated name would keep both bindings' saved keys in one entry (#1749's
         * review).
         */
        public String label() {
            return label;
        }

        /**
         * Mod name first, then the mod's own declaration order, so a load registers the same way.
         */
        @Override
        public int compareTo(DeclaredKey other) {
            int byMod = mod.compareTo(other.mod);
            return byMod != 0 ? byMod : key.id().compareTo(other.key.id());
        }
    }

    /** One declared configuration option, with its type, its default and its range. */
    public static final class Option {
        private final String key;
        private final String type;
        private final String title;
        private final String description;
        private final boolean defaultBool;
        private final double defaultNumber;
        private final int defaultColor;
        private final boolean hasMin;
        private final double min;
        private final boolean hasMax;
        private final double max;

        Option(
                String key,
                String type,
                String title,
                String description,
                boolean defaultBool,
                double defaultNumber,
                int defaultColor,
                boolean hasMin,
                double min,
                boolean hasMax,
                double max) {
            this.key = key;
            this.type = type;
            this.title = title;
            this.description = description;
            this.defaultBool = defaultBool;
            this.defaultNumber = defaultNumber;
            this.defaultColor = defaultColor;
            this.hasMin = hasMin;
            this.min = min;
            this.hasMax = hasMax;
            this.max = max;
        }

        /** The option's key, which the mod names in {@code Config}. */
        public String key() {
            return key;
        }

        /** One of {@code boolean}, {@code number} or {@code color}. */
        public String type() {
            return type;
        }

        /** The one-line title a screen would show, and the comment the config file carries. */
        public String title() {
            return title;
        }

        /** The sentence a screen would show under the title, and the config file's comment. */
        public String description() {
            return description;
        }

        public boolean defaultBool() {
            return defaultBool;
        }

        public double defaultNumber() {
            return defaultNumber;
        }

        /** The default color as 0xAARRGGBB, which only a {@code color} option has. */
        public int defaultColor() {
            return defaultColor;
        }

        public boolean hasMin() {
            return hasMin;
        }

        public double min() {
            return min;
        }

        public boolean hasMax() {
            return hasMax;
        }

        public double max() {
            return max;
        }

        /** Whether a number is inside the declared range, which a file's value has to be. */
        public boolean inRange(double value) {
            return (!hasMin || value >= min) && (!hasMax || value <= max);
        }

        /** The default as the text the config file carries for it. */
        public String defaultTextLine() {
            if ("boolean".equals(type)) {
                return defaultBool ? "true" : "false";
            }
            if ("number".equals(type)) {
                // A whole number inside long's range is written without a fraction; one outside it
                // keeps the double's own text, because the cast would saturate at the long's end
                // and the file would carry a value the mod never declared (#1749's review).
                if (defaultNumber == Math.rint(defaultNumber)
                        && !Double.isInfinite(defaultNumber)
                        && defaultNumber >= Long.MIN_VALUE
                        && defaultNumber <= Long.MAX_VALUE) {
                    return Long.toString((long) defaultNumber);
                }
                return Double.toString(defaultNumber);
            }
            return colorText(defaultColor);
        }
    }

    /** Everything the host acts on in one mod's {@code about.json}. */
    public static final class Mod {
        private final List<Key> keys;
        private final List<Option> options;

        Mod(List<Key> keys, List<Option> options) {
            this.keys = Collections.unmodifiableList(keys);
            this.options = Collections.unmodifiableList(options);
        }

        public List<Key> keys() {
            return keys;
        }

        /** The options by key, in the order the about.json declares them. */
        public List<Option> options() {
            return options;
        }

        /** The option a key names, or null when the mod declares no such option. */
        public Option option(String key) {
            for (Option option : options) {
                if (option.key.equals(key)) {
                    return option;
                }
            }
            return null;
        }
    }

    private static final Mod EMPTY = new Mod(new ArrayList<Key>(), new ArrayList<Option>());

    /** One mod's parsed metadata and the about.json's modification time it was read at. */
    private static final class Cached {
        final long modified;
        final Mod mod;

        Cached(long modified, Mod mod) {
            this.modified = modified;
            this.mod = mod;
        }
    }

    private static final Map<String, Cached> cache = new HashMap<>();

    @GrugGenerated("utility class: never instantiated")
    private GrugMetadata() {}

    /** The cache key of one mod directory, so two mods directories cannot collide. */
    private static String cacheKey(File modsDir, String mod) {
        return new File(modsDir, mod).getAbsolutePath();
    }

    /**
     * The metadata of a mod directory, read again only when its {@code about.json} changed.
     *
     * @param modsDir the directory the game loads grug mods from
     * @param mod the mod's directory name
     */
    public static Mod forMod(File modsDir, String mod) {
        File about = new File(new File(modsDir, mod), "about.json");
        long modified = about.lastModified();
        String key = cacheKey(modsDir, mod);
        Cached cached = cache.get(key);
        if (cached != null && cached.modified == modified) {
            return cached.mod;
        }
        Mod read = about.isFile() ? read(about) : EMPTY;
        cache.put(key, new Cached(modified, read));
        return read;
    }

    /** One mod's metadata, from the directories the running game uses. */
    public static Mod forCallerMod(String mod) {
        return forMod(GrugCore.getAdapter().getGrugModsDirectory(), mod);
    }

    /**
     * Every binding the mounted mods declare, sorted by mod and then by the mod's own order. A
     * loader registers one game binding per entry at client start, so a declared key is in the
     * Controls screen before a world is opened.
     *
     * <p>A mod added while the game runs is not in this list: registering a binding is a start-up
     * step the game offers once, so a new mod's key appears after a restart.
     */
    public static List<DeclaredKey> declaredKeys() {
        File modsDir = GrugCore.getAdapter().getGrugModsDirectory();
        List<DeclaredKey> declared = new ArrayList<>();
        File[] modDirs = modsDir.listFiles();
        if (modDirs == null) {
            return declared;
        }
        for (File modDir : modDirs) {
            if (!modDir.isDirectory()) continue;
            for (Key key : forMod(modsDir, modDir.getName()).keys()) {
                declared.add(new DeclaredKey(modDir.getName(), key));
            }
        }
        Collections.sort(declared);
        // A repeated display name carries its mod and id, in the same load order, so every binding
        // keeps a label of its own (#1749's review).
        Set<String> labels = new HashSet<>();
        for (DeclaredKey declaredKey : declared) {
            String label = declaredKey.label;
            while (!labels.add(label)) {
                label = label + " (" + declaredKey.mod + '.' + declaredKey.key.id() + ")";
            }
            declaredKey.label = label;
        }
        return declared;
    }

    /** Reads one {@code about.json}, or answers an empty metadata when it cannot be read. */
    public static Mod read(File aboutJson) {
        if (!aboutJson.isFile()) {
            return EMPTY;
        }
        try {
            String contents =
                    new String(Files.readAllBytes(aboutJson.toPath()), StandardCharsets.UTF_8);
            return read(contents);
        } catch (IOException | RuntimeException e) {
            // about_schema.py is what rejects a malformed about.json, and stopping the game over
            // metadata the host only reads for a key name or an option would be worse than
            // treating the mod as declaring neither.
            return EMPTY;
        }
    }

    /** Reads the JSON text of one {@code about.json}. */
    public static Mod read(String aboutJson) {
        JsonObject about = JsonParser.parseString(aboutJson).getAsJsonObject();
        List<Key> keys = new ArrayList<>();
        List<Option> options = new ArrayList<>();

        JsonElement declaredKeys = about.get("keys");
        if (declaredKeys != null && declaredKeys.isJsonArray()) {
            for (JsonElement element : declaredKeys.getAsJsonArray()) {
                Key key = readKey(element);
                if (key != null) keys.add(key);
            }
        }

        JsonElement declaredOptions = about.get("config");
        if (declaredOptions != null && declaredOptions.isJsonArray()) {
            for (JsonElement element : declaredOptions.getAsJsonArray()) {
                Option option = readOption(element);
                if (option != null) options.add(option);
            }
        }

        return new Mod(keys, options);
    }

    private static Key readKey(JsonElement element) {
        if (!element.isJsonObject()) return null;
        JsonObject object = element.getAsJsonObject();
        String id = text(object, "id");
        String name = text(object, "name");
        int code = keyCode(text(object, "default_key"));
        if (id == null || name == null || code < 0) return null;
        return new Key(id, name, code);
    }

    private static Option readOption(JsonElement element) {
        if (!element.isJsonObject()) return null;
        JsonObject object = element.getAsJsonObject();
        String key = text(object, "key");
        String type = text(object, "type");
        String title = text(object, "title");
        String description = text(object, "description");
        if (key == null || type == null || title == null || description == null) return null;

        JsonElement fallback = object.get("default");
        if (fallback == null) return null;
        boolean isBool = "boolean".equals(type);
        boolean isNumber = "number".equals(type);
        boolean isColor = "color".equals(type);
        if (!isBool && !isNumber && !isColor) return null;

        boolean defaultBool =
                fallback.isJsonPrimitive()
                        && fallback.getAsJsonPrimitive().isBoolean()
                        && fallback.getAsBoolean();
        double defaultNumber =
                fallback.isJsonPrimitive() && fallback.getAsJsonPrimitive().isNumber()
                        ? fallback.getAsDouble()
                        : 0.0;
        String defaultText =
                fallback.isJsonPrimitive() && fallback.getAsJsonPrimitive().isString()
                        ? fallback.getAsString()
                        : "";
        // A colour option's default has to parse, and every other option's is the sentinel: an
        // option that is not a colour has no colour text to answer with.
        long color = colorCode(defaultText);
        if (isColor && color == INVALID_COLOR) return null;
        int defaultColor = (int) color;

        boolean hasMin = numberMember(object, "min") != null;
        double min = hasMin ? numberMember(object, "min") : 0.0;
        boolean hasMax = numberMember(object, "max") != null;
        double max = hasMax ? numberMember(object, "max") : 0.0;

        return new Option(
                key,
                type,
                title,
                description,
                defaultBool,
                defaultNumber,
                defaultColor,
                hasMin,
                min,
                hasMax,
                max);
    }

    private static Double numberMember(JsonObject object, String name) {
        JsonElement element = object.get(name);
        if (element == null
                || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isNumber()) {
            return null;
        }
        return element.getAsDouble();
    }

    private static String text(JsonObject object, String name) {
        JsonElement element = object.get(name);
        if (element == null
                || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isString()) {
            return null;
        }
        return element.getAsString();
    }

    /**
     * The number a bad color text is, which no 0xAARRGGBB value can be: a valid one is 0 to
     * 0xFFFFFFFF, so a negative {@code long} is outside the range every colour is in.
     */
    public static final long INVALID_COLOR = -1L;

    /** The opaque magenta a color option answers when the mod declares nothing usable. */
    public static final int FALLBACK_COLOR = 0xFFFF00FF;

    /**
     * Parses a colour text into 0xAARRGGBB: {@code #RRGGBB} is opaque, and {@code #AARRGGBB}
     * carries its own alpha. The reference's ConfigColors trims the text, accepts the leading
     * {@code #} or not, and falls back to the default on anything else; this answers {@link
     * #INVALID_COLOR} instead, which the caller turns into the default. The answer is a {@code
     * long} so that opaque white, {@code #FFFFFF}, is not mistaken for the sentinel.
     */
    public static long colorCode(String text) {
        if (text == null) return INVALID_COLOR;
        String value = text.trim();
        if (value.startsWith("#")) {
            value = value.substring(1);
        }
        if (value.length() != 6 && value.length() != 8) return INVALID_COLOR;
        long parsed = 0;
        for (int i = 0; i < value.length(); i++) {
            int digit = Character.digit(value.charAt(i), 16);
            if (digit < 0) return INVALID_COLOR;
            parsed = (parsed << 4) | digit;
        }
        if (value.length() == 6) {
            return 0xFF000000L | parsed;
        }
        return parsed;
    }

    /** The text a colour is written as in a config file: {@code #RRGGBB} or {@code #AARRGGBB}. */
    public static String colorText(int argb) {
        if (((argb >>> 24) & 0xFF) == 0xFF) {
            return String.format("#%06X", argb & 0xFFFFFF);
        }
        return String.format("#%08X", argb);
    }

    /**
     * The upper case character code of a one-character key name, which is the shape a declaration's
     * {@code default_key} has, or -1 when the name is anything else. Letters and digits are the
     * keys a world key and a binding are delivered for, so they are the names a declaration may
     * use.
     */
    public static int keyCode(String name) {
        if (name == null || name.length() != 1) return -1;
        char character = Character.toUpperCase(name.charAt(0));
        if ((character >= '0' && character <= '9') || (character >= 'A' && character <= 'Z')) {
            return character;
        }
        return -1;
    }
}
