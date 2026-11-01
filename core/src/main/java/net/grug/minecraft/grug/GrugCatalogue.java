package net.grug.minecraft.grug;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.LongConsumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The mods a release publishes beside the jar, and how one is installed from it (#511).
 *
 * <p>A release attaches {@code <mod>.zip} for each mod and an {@code index.json} naming each zip
 * with its SHA-256, which {@code universal_jar.py mod-zips} writes:
 *
 * <pre>{"commit": "9f2c1a...", "mods": {"buildcraft": {"zip": "buildcraft.zip", "sha256": "..."}}}
 * </pre>
 *
 * A mod unchanged since an older release has an extra {@code "url"}, an absolute http(s) URL ending
 * in {@code /<zip>} that points at the older release's asset, because a release attaches only the
 * zips whose bytes changed (#523). Without {@code "url"} the zip sits beside the index.
 *
 * <p>The index is read from GitHub's release CDN, not its API, so the API's hourly limit for
 * unauthenticated requests does not apply. An install downloads the zip, checks it against the
 * index, and only then unpacks it, into a hidden directory that is renamed into place, so a failure
 * anywhere installs nothing.
 */
public final class GrugCatalogue {
    /** The system property that points the game at another index, for a test or a mirror. */
    public static final String URL_PROPERTY = "grug.catalogue.url";

    /** The index of the latest release. */
    public static final String INDEX_URL =
            "https://github.com/grug-lang/grug-for-minecraft/releases/latest/download/index.json";

    /** More than an index of any mod count a release holds, so a server cannot fill the memory. */
    static final long INDEX_LIMIT = 16L * 1024 * 1024;

    /** More than any mod unpacks to, so a zip cannot fill the disk. */
    static final long UNPACKED_LIMIT = 512L * 1024 * 1024;

    /** One mod of the catalogue. */
    public static final class Item {
        final String zipUrl;
        final String sha256;
        final long size;

        Item(String zipUrl, String sha256, long size) {
            this.zipUrl = zipUrl;
            this.sha256 = sha256;
            this.size = size;
        }

        /** The size of the zip in bytes, which the index states so the screen can show it. */
        public long size() {
            return size;
        }
    }

    private final GrugHttp.Fetcher http;
    private final String indexUrl;
    private final long unpackedLimit;

    public GrugCatalogue(GrugHttp.Fetcher http, String indexUrl) {
        this(http, indexUrl, UNPACKED_LIMIT);
    }

    GrugCatalogue(GrugHttp.Fetcher http, String indexUrl, long unpackedLimit) {
        this.http = http;
        this.indexUrl = indexUrl;
        this.unpackedLimit = unpackedLimit;
    }

    /** The catalogue the game reads: the latest release's, unless the property names another. */
    public static GrugCatalogue latest() {
        return new GrugCatalogue(
                GrugHttp.urlConnection(), System.getProperty(URL_PROPERTY, INDEX_URL));
    }

    /** The mods of the index by name. */
    public Map<String, Item> fetch() throws IOException {
        String text =
                new String(http.get(indexUrl, INDEX_LIMIT, bytes -> {}), StandardCharsets.UTF_8);
        String base = indexUrl.substring(0, indexUrl.lastIndexOf('/') + 1);
        Map<String, Item> items = new TreeMap<>();
        try {
            JsonObject mods =
                    JsonParser.parseString(text).getAsJsonObject().getAsJsonObject("mods");
            if (mods == null) {
                throw new JsonParseException("no \"mods\" object");
            }
            for (Map.Entry<String, JsonElement> mod : mods.entrySet()) {
                if (!GrugModManager.validName(mod.getKey())) {
                    throw new JsonParseException(mod.getKey() + " is not a mod name");
                }
                JsonObject entry = mod.getValue().getAsJsonObject();
                String zip = entry.get("zip").getAsString();
                String sha256 = entry.get("sha256").getAsString();
                long size = entry.get("size").getAsLong();
                if (!GrugModManager.validName(zip)
                        || !sha256.matches("[0-9a-fA-F]{64}")
                        || size < 1) {
                    throw new JsonParseException(
                            mod.getKey() + " names a bad zip, SHA-256 or size");
                }
                // A mod that has not changed since an older release points at that release's
                // asset with an absolute url, so the release does not attach its zip again (#523).
                String zipUrl = base + zip;
                if (entry.has("url")) {
                    zipUrl = entry.get("url").getAsString();
                    if (!zipUrl.matches("https?://[^\\s]+/" + java.util.regex.Pattern.quote(zip))) {
                        throw new JsonParseException(mod.getKey() + " names a bad url");
                    }
                }
                items.put(mod.getKey(), new Item(zipUrl, sha256.toLowerCase(), size));
            }
        } catch (JsonParseException
                | IllegalStateException
                | NullPointerException
                | ClassCastException
                | UnsupportedOperationException
                | NumberFormatException e) {
            throw new IOException(indexUrl + " is not a mod index: " + e.getMessage(), e);
        }
        return items;
    }

    /**
     * Downloads a mod, checks it against the index and unpacks it into {@code target}, which then
     * holds a directory of the mod's name. Fails, with nothing installed, on a failed download, a
     * zip that is not the index's, or a zip with an entry outside {@code <mod>/}.
     */
    public void install(String mod, Item item, Path target) throws IOException {
        install(mod, item, target, bytes -> {});
    }

    /**
     * As {@link #install(String, Item, Path)}, telling {@code progress} how many bytes of the zip
     * have been downloaded. A zip longer than the index says it is fails the download.
     */
    public void install(String mod, Item item, Path target, LongConsumer progress)
            throws IOException {
        byte[] zip = http.get(item.zipUrl, item.size, progress);
        if (!item.sha256.equals(sha256(zip))) {
            throw new IOException(
                    "the download of " + mod + " does not match the SHA-256 of the index");
        }
        Path staging = target.resolve("." + mod + ".installing");
        GrugModsExtractor.deleteTree(staging);
        try {
            unpack(zip, mod, staging);
            Files.move(staging, target.resolve(mod));
        } finally {
            GrugModsExtractor.deleteTree(staging);
        }
    }

    private void unpack(byte[] zip, String mod, Path staging) throws IOException {
        Files.createDirectories(staging);
        long total = 0;
        boolean any = false;
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                String name = entry.getName();
                Path destination =
                        name.startsWith(mod + "/")
                                        && name.indexOf('\\') < 0
                                        && name.indexOf('\0') < 0
                                ? staging.resolve(name.substring(mod.length() + 1)).normalize()
                                : null;
                if (destination == null || !destination.startsWith(staging)) {
                    throw new IOException(
                            "the zip of " + mod + " holds " + name + ", outside " + mod + "/");
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(destination);
                    continue;
                }
                Files.createDirectories(destination.getParent());
                try (OutputStream out = Files.newOutputStream(destination)) {
                    byte[] chunk = new byte[8192];
                    for (int read = in.read(chunk); read >= 0; read = in.read(chunk)) {
                        total += read;
                        if (total > unpackedLimit) {
                            throw new IOException(
                                    "the zip of " + mod + " unpacks to too many bytes");
                        }
                        out.write(chunk, 0, read);
                    }
                }
                any = true;
            }
        }
        if (!any) {
            throw new IOException("the zip of " + mod + " holds no file");
        }
    }

    private static String sha256(byte[] data) {
        return digest("SHA-256", data);
    }

    static String digest(String algorithm, byte[] data) {
        try {
            byte[] digest = MessageDigest.getInstance(algorithm).digest(data);
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw Grug.fatal("This Java has no " + algorithm, e);
        }
    }
}
