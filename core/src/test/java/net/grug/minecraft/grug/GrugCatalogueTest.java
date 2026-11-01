package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Covers the mod catalogue of a release against a local server: reading its index, and installing a
 * mod from it, with the failures that must install nothing (#511).
 */
class GrugCatalogueTest {

    @TempDir Path target;

    private HttpServer server;
    private final Map<String, byte[]> served = new HashMap<>();
    private String base;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    byte[] body = served.get(exchange.getRequestURI().getPath());
                    if (body == null) {
                        exchange.sendResponseHeaders(404, -1);
                    } else {
                        exchange.sendResponseHeaders(200, body.length);
                        exchange.getResponseBody().write(body);
                    }
                    exchange.close();
                });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort() + "/release/";
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    static byte[] zip(String... namesAndContents) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (int i = 0; i < namesAndContents.length; i += 2) {
                ZipEntry entry = new ZipEntry(namesAndContents[i]);
                // ZipOutputStream writes the wall clock into an entry that has no time of its own,
                // in steps of two seconds, so the same zip built later held other bytes and another
                // SHA-256 (#828). Zero is before 1980, the earliest date an entry records, so every
                // time zone writes the same one.
                entry.setTime(0);
                zip.putNextEntry(entry);
                zip.write(namesAndContents[i + 1].getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    static String sha256(byte[] data) throws Exception {
        StringBuilder hex = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(data)) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    /** Serves a release with the mod alpha, and returns its catalogue. */
    private GrugCatalogue serveAlpha(byte[] zip, String sha) {
        served.put("/release/alpha.zip", zip);
        served.put(
                "/release/index.json",
                ("{\"commit\":\"abc\",\"mods\":{\"alpha\":{\"zip\":\"alpha.zip\",\"size\":"
                                + zip.length
                                + ",\"sha256\":\""
                                + sha
                                + "\"}}}")
                        .getBytes(StandardCharsets.UTF_8));
        return new GrugCatalogue(GrugHttp.urlConnection(), base + "index.json");
    }

    private void installAlpha(GrugCatalogue catalogue) throws IOException {
        catalogue.install("alpha", catalogue.fetch().get("alpha"), target);
    }

    @Test
    void aZipOfTheSameEntriesHoldsTheSameBytesWheneverItIsBuilt() throws Exception {
        byte[] first = zip("alpha/about.json", "{}", "alpha/code/a.grug", "a");
        // A zip entry's time moves in steps of two seconds, so wait for the step to turn over:
        // a zip stamped with the wall clock holds other bytes, and so another hash, after that.
        long now = System.currentTimeMillis();
        Thread.sleep((now / 2000 + 1) * 2000 - now + 100);

        assertArrayEquals(
                first,
                zip("alpha/about.json", "{}", "alpha/code/a.grug", "a"),
                "the bytes of a zip must depend on its entries alone");
    }

    @Test
    void installsAModFromTheIndexAfterCheckingItsHash() throws Exception {
        byte[] zip = zip("alpha/about.json", "{}", "alpha/code/a.grug", "a");

        installAlpha(serveAlpha(zip, sha256(zip)));

        assertEquals("a", Files.readString(target.resolve("alpha/code/a.grug")));
        assertTrue(Files.isRegularFile(target.resolve("alpha/about.json")));
        assertFalse(Files.exists(target.resolve(".alpha.installing")));
    }

    @Test
    void acceptsAnUpperCaseHashAndADirectoryEntry() throws Exception {
        byte[] zip = zip("alpha/", "", "alpha/about.json", "{}");

        installAlpha(serveAlpha(zip, sha256(zip).toUpperCase()));

        assertTrue(Files.isRegularFile(target.resolve("alpha/about.json")));
    }

    @Test
    void tellsHowManyBytesOfTheZipHaveBeenDownloaded() throws Exception {
        byte[] zip = zip("alpha/about.json", "{}");
        GrugCatalogue catalogue = serveAlpha(zip, sha256(zip));
        java.util.List<Long> seen = new java.util.ArrayList<>();

        catalogue.install("alpha", catalogue.fetch().get("alpha"), target, seen::add);

        assertEquals(java.util.List.of((long) zip.length), seen);
    }

    @Test
    void aZipLongerThanTheIndexSaysItIsFailsTheDownload() throws Exception {
        byte[] zip = zip("alpha/about.json", "{}");
        GrugCatalogue catalogue = serveAlpha(zip, sha256(zip));
        served.put(
                "/release/alpha.zip",
                zip(
                        "alpha/about.json",
                        "{}",
                        "alpha/more.txt",
                        "padding to be longer than the index says"));

        IOException error =
                assertThrows(
                        IOException.class,
                        () -> catalogue.install("alpha", catalogue.fetch().get("alpha"), target));

        assertTrue(
                error.getMessage().contains("more than " + zip.length + " bytes"),
                error.getMessage());
        assertEquals(0, Files.list(target).count());
    }

    @Test
    void aHashThatDoesNotMatchInstallsNothing() throws Exception {
        byte[] zip = zip("alpha/about.json", "{}");

        IOException error =
                assertThrows(
                        IOException.class, () -> installAlpha(serveAlpha(zip, "0".repeat(64))));

        assertTrue(error.getMessage().contains("does not match"), error.getMessage());
        assertEquals(0, Files.list(target).count());
    }

    @Test
    void aMissingZipInstallsNothing() throws Exception {
        byte[] zip = zip("alpha/about.json", "{}");
        GrugCatalogue catalogue = serveAlpha(zip, sha256(zip));
        served.remove("/release/alpha.zip");

        IOException error = assertThrows(IOException.class, () -> installAlpha(catalogue));

        assertTrue(error.getMessage().contains("answered 404"), error.getMessage());
        assertEquals(0, Files.list(target).count());
    }

    @Test
    void aServerThatIsNotThereFails() {
        server.stop(0);
        GrugCatalogue catalogue = new GrugCatalogue(GrugHttp.urlConnection(), base + "index.json");

        assertThrows(IOException.class, catalogue::fetch);
    }

    @Test
    void aZipWithAnEntryOutsideTheModInstallsNothing() throws Exception {
        for (String name :
                new String[] {
                    "beta/about.json",
                    "alpha/../evil.txt",
                    "alpha/a\\evil.txt",
                    "alpha/a\0b",
                    "evil.txt"
                }) {
            byte[] zip = zip("alpha/about.json", "{}", name, "x");

            IOException error =
                    assertThrows(
                            IOException.class, () -> installAlpha(serveAlpha(zip, sha256(zip))));

            assertTrue(
                    error.getMessage().contains("outside alpha/"),
                    name + ": " + error.getMessage());
            assertEquals(0, Files.list(target).count(), name);
        }
    }

    @Test
    void aZipWithNoFileInstallsNothing() throws Exception {
        byte[] zip = zip("alpha/", "");

        IOException error =
                assertThrows(IOException.class, () -> installAlpha(serveAlpha(zip, sha256(zip))));

        assertTrue(error.getMessage().contains("holds no file"), error.getMessage());
        assertEquals(0, Files.list(target).count());
    }

    @Test
    void aZipThatUnpacksToTooMuchInstallsNothing() throws Exception {
        byte[] zip = zip("alpha/big.txt", "0123456789");
        GrugCatalogue catalogue = serveAlpha(zip, sha256(zip));
        GrugCatalogue small = new GrugCatalogue(GrugHttp.urlConnection(), base + "index.json", 5);

        IOException error =
                assertThrows(
                        IOException.class,
                        () -> small.install("alpha", catalogue.fetch().get("alpha"), target));

        assertTrue(error.getMessage().contains("too many bytes"), error.getMessage());
        assertEquals(0, Files.list(target).count());
    }

    @Test
    void anInstallOverAnInstalledModFailsAndKeepsIt() throws Exception {
        byte[] zip = zip("alpha/about.json", "new");
        Files.createDirectories(target.resolve("alpha"));
        Files.writeString(target.resolve("alpha/about.json"), "old");

        assertThrows(IOException.class, () -> installAlpha(serveAlpha(zip, sha256(zip))));

        assertEquals("old", Files.readString(target.resolve("alpha/about.json")));
        assertFalse(Files.exists(target.resolve(".alpha.installing")));
    }

    @Test
    void anIndexThatIsNotAnIndexIsRefused() throws Exception {
        String good = "{\"zip\":\"a.zip\",\"size\":5,\"sha256\":\"" + "0".repeat(64) + "\"}";
        String[] bad = {
            "[]",
            "{}",
            "{\"mods\":{\"../x\":" + good + "}}",
            "{\"mods\":{\"alpha\":[]}}",
            "{\"mods\":{\"alpha\":{\"zip\":\"a.zip\",\"sha256\":\"" + "0".repeat(64) + "\"}}}",
            "{\"mods\":{\"alpha\":{\"zip\":\"a.zip\",\"size\":0,\"sha256\":\""
                    + "0".repeat(64)
                    + "\"}}}",
            "{\"mods\":{\"alpha\":{\"zip\":\"a.zip\",\"size\":\"big\",\"sha256\":\""
                    + "0".repeat(64)
                    + "\"}}}",
            "{\"mods\":\"x\"}",
            "{\"mods\":[]}",
            "{\"mods\":{\"alpha\":{\"zip\":null,\"size\":5,\"sha256\":\""
                    + "0".repeat(64)
                    + "\"}}}",
            "{\"mods\":{\"alpha\":{\"zip\":{},\"size\":5,\"sha256\":\"" + "0".repeat(64) + "\"}}}",
            "{\"mods\":{\"alpha\":{\"sha256\":\"" + "0".repeat(64) + "\"}}}",
            "{\"mods\":{\"alpha\":{\"zip\":\"../a.zip\",\"size\":5,\"sha256\":\""
                    + "0".repeat(64)
                    + "\"}}}",
            "{\"mods\":{\"alpha\":{\"zip\":\"a.zip\",\"size\":5,\"sha256\":\"abc\"}}}",
            "{",
        };
        GrugCatalogue catalogue = new GrugCatalogue(GrugHttp.urlConnection(), base + "index.json");
        for (String text : bad) {
            served.put("/release/index.json", text.getBytes(StandardCharsets.UTF_8));

            IOException error = assertThrows(IOException.class, catalogue::fetch, text);

            assertTrue(error.getMessage().contains("is not a mod index"), error.getMessage());
        }
    }

    @Test
    void readsTheModsOfTheIndexWithTheirUrls() throws Exception {
        byte[] zip = zip("alpha/about.json", "{}");

        Map<String, GrugCatalogue.Item> items = serveAlpha(zip, sha256(zip)).fetch();

        assertEquals(java.util.List.of("alpha"), new java.util.ArrayList<>(items.keySet()));
        assertEquals(base + "alpha.zip", items.get("alpha").zipUrl);
        assertEquals(zip.length, items.get("alpha").size());
    }

    @Test
    void theGameReadsTheLatestReleaseOfThisRepositoryUnlessToldOtherwise() throws Exception {
        assertEquals(
                "https://github.com/grug-lang/grug-for-minecraft/releases/latest/download/index.json",
                GrugCatalogue.INDEX_URL);
        byte[] zip = zip("alpha/about.json", "{}");
        serveAlpha(zip, sha256(zip));
        System.setProperty(GrugCatalogue.URL_PROPERTY, base + "index.json");
        try {
            assertEquals("[alpha]", GrugCatalogue.latest().fetch().keySet().toString());
        } finally {
            System.clearProperty(GrugCatalogue.URL_PROPERTY);
        }
    }

    @Test
    void aJavaWithoutTheDigestIsFatal() {
        IllegalStateException error =
                assertThrows(
                        IllegalStateException.class,
                        () -> GrugCatalogue.digest("NOPE", new byte[0]));

        assertTrue(error.getMessage().contains("no NOPE"), error.getMessage());
        try {
            GrugErrors.throwPendingFatal();
        } catch (IllegalStateException expected) {
            // The fatal this test provoked, cleared so it cannot outlive it.
        }
    }
}
