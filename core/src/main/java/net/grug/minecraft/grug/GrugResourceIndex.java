package net.grug.minecraft.grug;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The filesystem half of the generated resource pack.
 *
 * <p>Lives in {@code net.grug.*} so Java tests can drive it against a scratch directory instead of
 * a running game; the loaders' {@code ResourcePack} classes are thin adapters over this.
 */
public final class GrugResourceIndex {
    private GrugResourceIndex() {}

    /** The first mod directory that has {@code baseDir/namespace/path}, or null. */
    public static File findResource(File modsDir, String baseDir, String namespace, String path) {
        File[] modDirs = modsDir.listFiles(File::isDirectory);
        if (modDirs == null) return null;
        for (File modDir : modDirs) {
            File file = new File(modDir, baseDir + "/" + namespace + "/" + path);
            if (file.exists()) return file;
        }
        return null;
    }

    /** The first mod directory that has {@code relativePath} directly, or null. */
    public static File findDirectResource(File modsDir, String relativePath) {
        File[] modDirs = modsDir.listFiles(File::isDirectory);
        if (modDirs == null) return null;
        for (File modDir : modDirs) {
            File file = new File(modDir, relativePath);
            if (file.exists()) return file;
        }
        return null;
    }

    /** Every namespace that has an {@code assets/} or {@code data/} directory under any mod. */
    public static Set<String> findNamespaces(File modsDir) {
        Set<String> namespaces = new HashSet<>();
        File[] modDirs = modsDir.listFiles(File::isDirectory);
        if (modDirs == null) return namespaces;
        for (File modDir : modDirs) {
            namespaces.addAll(namesUnder(new File(modDir, "assets")));
            namespaces.addAll(namesUnder(new File(modDir, "data")));
        }
        return namespaces;
    }

    private static Set<String> namesUnder(File parent) {
        Set<String> names = new HashSet<>();
        File[] dirs = parent.listFiles(File::isDirectory);
        if (dirs != null) {
            for (File dir : dirs) {
                names.add(dir.getName());
            }
        }
        return names;
    }

    /**
     * The paths, relative to {@code baseDir/namespace}, of every regular file under {@code
     * baseDir/namespace/path} in any mod.
     *
     * <p>A directory that cannot be walked aborts rather than being reported and skipped: the
     * caller is about to hand the game a list of what exists, and a list missing a directory it
     * could not read is one the game cannot tell from a directory that is not there.
     */
    public static List<String> findResources(
            File modsDir, String baseDir, String namespace, String path) {
        List<String> found = new ArrayList<>();
        File[] modDirs = modsDir.listFiles(File::isDirectory);
        if (modDirs == null) return found;
        for (File modDir : modDirs) {
            File targetDir = new File(modDir, baseDir + "/" + namespace + "/" + path);
            if (!isDirectory(targetDir)) continue;

            Path base = new File(modDir, baseDir + "/" + namespace).toPath();
            collectResources(base, targetDir, found);
        }
        return found;
    }

    /** Whether {@code file} is an existing directory. */
    @GrugGenerated("directory check")
    private static boolean isDirectory(File file) {
        return file.exists() && file.isDirectory();
    }

    /** Adds every regular file under {@code targetDir}, relative to {@code base}. */
    @GrugGenerated("resource walk: a directory that cannot be walked is not forceable")
    private static void collectResources(Path base, File targetDir, List<String> found) {
        try (Stream<Path> stream = Files.walk(targetDir.toPath())) {
            stream.filter(Files::isRegularFile)
                    .forEach(p -> found.add(base.relativize(p).toString().replace('\\', '/')));
        } catch (Exception e) {
            throw Grug.fatal("Failed to walk the resource directory " + targetDir, e);
        }
    }

    /**
     * Merges every mod's {@code <langName>.json} (or the lowercased variant) into one legacy {@code
     * .lang} byte stream, rewriting modern {@code block.}/{@code item.} keys. Returns null when no
     * mod ships the language.
     *
     * <p>A language file that cannot be read aborts rather than being skipped: the merge is the
     * only place a translation reaches the game, so dropping the file silently renames whatever it
     * named. A file that parses but gives a key a value that is not a string reports that one key
     * and merges the rest, because the offending key is the only thing the output loses.
     */
    public static byte[] mergeLang(File modsDir, String langName) {
        JsonObject json = new JsonObject();
        List<String> languages = new ArrayList<>();

        File[] modDirs = modsDir.listFiles(File::isDirectory);
        if (modDirs == null) return null;

        for (File modDir : modDirs) {
            File assetsDir = new File(modDir, "assets");
            File[] nsDirs = assetsDir.listFiles(File::isDirectory);
            if (nsDirs == null) continue;

            for (File nsDir : nsDirs) {
                File jsonFile = new File(nsDir, "lang/" + langName.toLowerCase() + ".json");
                if (!jsonFile.exists()) {
                    jsonFile = new File(nsDir, "lang/" + langName + ".json");
                }
                if (!jsonFile.exists()) continue;

                languages.add(jsonFile.getPath());
                mergeLangFile(jsonFile, json);
            }
        }

        if (languages.isEmpty()) return null;

        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue().getAsString();

            if (key.startsWith("block.")) {
                key = "tile." + key.substring(6);
                if (!key.endsWith(".name")) key += ".name";
            } else if (key.startsWith("item.")) {
                if (!key.endsWith(".name")) key += ".name";
            }

            out.append(key).append("=").append(value).append("\n");
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Reads one language file into {@code merged}.
     *
     * <p>Kept apart so the merge above reads as the loop it is, and so the failure path has one
     * message of its own to assert on.
     */
    private static void mergeLangFile(File jsonFile, JsonObject merged) {
        try (Reader reader =
                new InputStreamReader(new FileInputStream(jsonFile), StandardCharsets.UTF_8)) {
            JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
                // The merge writes every value as text, so a value that is not a primitive has no
                // line to become. Report that one key and leave it out rather than renaming the
                // whole file's contents or crashing on a JSON shape the format does not use.
                if (!entry.getValue().isJsonPrimitive()) {
                    GrugModTreeDefect.report(
                            "Language file '"
                                    + jsonFile
                                    + "' gives '"
                                    + entry.getKey()
                                    + "' a value that is not a string.");
                    continue;
                }
                merged.add(entry.getKey(), entry.getValue());
            }
        } catch (Exception e) {
            throw Grug.fatal("Failed to read the language file " + jsonFile, e);
        }
    }
}
