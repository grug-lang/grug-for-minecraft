package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.grug.minecraft.core.GrugCore;
import net.grug.minecraft.core.ModLoaderAdapter;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Covers the host-owned configuration file: the defaults it is written with, the values it parses,
 * the defaults a bad or missing value answers, and the cache that picks up an edit.
 */
class GrugConfigTest {

    @TempDir Path tmp;

    private Object savedAdapter;
    private Consumer<String> savedReporter;
    private final List<String> reports = new ArrayList<>();

    private static final String DECLARATION =
            "{\"config\": ["
                    + "{\"key\": \"on\", \"type\": \"boolean\", \"title\": \"On\","
                    + " \"description\": \"Whether it is on.\", \"default\": true},"
                    + "{\"key\": \"bare\", \"type\": \"boolean\", \"title\": \"Bare\","
                    + " \"description\": \"\", \"default\": false},"
                    + "{\"key\": \"count\", \"type\": \"number\", \"title\": \"Count\","
                    + " \"description\": \"How many.\", \"default\": 4, \"min\": 1,"
                    + " \"max\": 9},"
                    + "{\"key\": \"tint\", \"type\": \"color\", \"title\": \"Tint\","
                    + " \"description\": \"Which.\", \"default\": \"#112233\"}]}";

    @BeforeEach
    void setUp() throws Exception {
        savedReporter = GrugConfig.reporter;
        GrugConfig.reporter = reports::add;
        clearStatic("cache");
        clearStatic("reported");
    }

    @AfterEach
    void tearDown() throws Exception {
        GrugConfig.reporter = savedReporter;
        clearStatic("cache");
        clearStatic("reported");
        reports.clear();
        if (savedAdapter != null) {
            Field field = GrugCore.class.getDeclaredField("adapter");
            field.setAccessible(true);
            field.set(null, savedAdapter);
            savedAdapter = null;
        }
    }

    private static void clearStatic(String name) throws Exception {
        Field field = GrugConfig.class.getDeclaredField(name);
        field.setAccessible(true);
        Object value = field.get(null);
        if (value instanceof Map) {
            ((Map<?, ?>) value).clear();
        } else {
            ((java.util.Collection<?>) value).clear();
        }
    }

    private static GrugMetadata.Mod metadata() {
        return GrugMetadata.read(DECLARATION);
    }

    @Test
    void theFileLivesUnderTheConfiguredDirectory() {
        File file = GrugConfig.file(tmp.toFile(), "mod");
        assertEquals(new File(new File(new File(tmp.toFile(), "config"), "grug"), "mod.yml"), file);
    }

    @Test
    void parsingKeepsEveryValueAndSkipsWhatIsNotOne() throws Exception {
        File file = tmp.resolve("mod.yml").toFile();
        Files.write(
                file.toPath(),
                ("# a comment\n"
                                + "\n"
                                + "on: true\n"
                                + "  count :  3  \n"
                                + "no colon here\n"
                                + ": no key\n"
                                + " : no key either\n"
                                + "url: http://x:80/\n")
                        .getBytes(StandardCharsets.UTF_8));

        Map<String, String> values = GrugConfig.parse(file);

        assertEquals("true", values.get("on"));
        assertEquals("3", values.get("count"));
        assertEquals("http://x:80/", values.get("url"));
        assertFalse(values.containsKey(""));
        assertFalse(values.containsKey("no colon here"));
        assertFalse(values.containsKey("no key either"));
        assertTrue(GrugConfig.parse(tmp.resolve("absent.yml").toFile()).isEmpty());
        assertTrue(GrugConfig.parse(tmp.toFile()).isEmpty(), "A directory is not a config file.");
    }

    @Test
    void theDefaultsAreWrittenWithTheirComments() throws Exception {
        File config = GrugConfig.file(tmp.toFile(), "written");

        GrugConfig.writeDefaults(config, metadata());

        List<String> lines = Files.readAllLines(config.toPath(), StandardCharsets.UTF_8);
        assertTrue(lines.get(0).startsWith("# Every option"), lines.get(0));
        assertTrue(lines.contains("# On: Whether it is on."), lines.toString());
        assertTrue(lines.contains("# Bare"), lines.toString());
        assertTrue(lines.contains("on: true"));
        assertTrue(lines.contains("bare: false"));
        assertTrue(lines.contains("count: 4"));
        assertTrue(lines.contains("tint: #112233"));

        GrugConfig.writeDefaults(config, metadata());
        assertEquals(
                lines,
                Files.readAllLines(config.toPath(), StandardCharsets.UTF_8),
                "An existing file is written again with the same text.");

        File relative = new File("grug-config-relative.yml");
        GrugConfig.writeDefaults(relative, metadata());
        assertTrue(relative.isFile());
        assertTrue(relative.delete(), "The test cleans up after itself.");
    }

    @Test
    void noFileIsWrittenForAModThatDeclaresNothing() throws Exception {
        File config = GrugConfig.file(tmp.toFile(), "empty");

        GrugConfig.writeDefaults(config, GrugMetadata.read("{}"));

        assertFalse(config.exists());
    }

    @Test
    void aParentPathThatIsAFileIsAnError() throws Exception {
        File config = GrugConfig.file(tmp.toFile(), "blocked");
        File grugDir = config.getParentFile();
        assertTrue(grugDir.getParentFile().mkdirs());
        Files.write(grugDir.toPath(), "x".getBytes(StandardCharsets.UTF_8));

        assertThrows(IOException.class, () -> GrugConfig.writeDefaults(config, metadata()));
    }

    @Test
    void aBadValueAnswersTheDeclaredDefaultAndAMissingOneReportsOnce() {
        Map<String, String> file = new HashMap<>();
        file.put("on", "yes");
        file.put("count", "99");
        file.put("tint", "chartreuse");
        GrugConfig.Values values = new GrugConfig.Values("m", metadata(), file);

        assertEquals(4, values.options().size());
        assertTrue(values.declares("on"));
        assertFalse(values.declares("nope"));
        assertEquals("count", values.option("count").key());
        assertEquals(null, values.option("nope"));

        assertTrue(values.bool("on"), "A boolean the file does not spell is the default.");
        assertFalse(values.bool("bare"));
        assertFalse(values.bool("count"), "A bool read from a number option answers its default.");

        assertEquals(4.0, values.number("count"), "An out-of-range value is the default.");
        assertEquals(0.0, values.number("on"), "A number read from a boolean is 0.");
        assertEquals(0xFF112233, values.color("tint"), "A bad colour is the declared default.");
        assertEquals(-1, values.color("on"), "A colour read from a boolean has no colour text.");
        assertTrue(reports.isEmpty(), "Only an undeclared option reports.");

        assertFalse(values.bool("nope"));
        assertEquals(0.0, values.number("nope"));
        assertEquals(0xFFFF00FF, values.color("nope"));
        assertEquals(1, reports.size(), "The same typo is reported once.");
        assertTrue(reports.get(0).contains("declares no option named nope"), reports.get(0));
    }

    @Test
    void aUsableValueIsReadFromTheFile() {
        Map<String, String> file = new HashMap<>();
        file.put("on", " FALSE ");
        file.put("count", "6");
        file.put("tint", "80112233");
        GrugConfig.Values values = new GrugConfig.Values("m", metadata(), file);

        assertFalse(values.bool("on"));
        assertEquals(6.0, values.number("count"));
        assertEquals(0x80112233, values.color("tint"));
        assertTrue(reports.isEmpty());

        assertEquals(
                0xFF112233,
                new GrugConfig.Values("m", metadata(), new HashMap<String, String>()).color("tint"),
                "An absent colour is the declared default.");
    }

    @Test
    void aNumberNoParserReadsIsTheDefault() {
        Map<String, String> file = new HashMap<>();
        file.put("count", "NaN");
        assertEquals(4.0, new GrugConfig.Values("m", metadata(), file).number("count"));

        file.put("count", "many");
        assertEquals(4.0, new GrugConfig.Values("m", metadata(), file).number("count"));

        file.put("count", "Infinity");
        assertEquals(4.0, new GrugConfig.Values("m", metadata(), file).number("count"));
    }

    @Test
    void theFirstReadWritesTheFileAndALaterEditIsSeen() throws Exception {
        File gameDir = tmp.resolve("game").toFile();
        File modsDir = new File(gameDir, "mods");
        assertTrue(modsDir.mkdirs());
        writeAbout(modsDir, "live", DECLARATION);
        installAdapter(gameDir, modsDir);

        GrugConfig.Values values = GrugConfig.forMod("live");
        File config = GrugConfig.file(gameDir, "live");
        assertTrue(config.isFile(), "The first read writes the declared defaults.");
        assertEquals(4.0, values.number("count"));
        assertTrue(GrugConfig.forMod("live") == values, "The second read is cached.");

        Files.write(config.toPath(), "count: 9\n".getBytes(StandardCharsets.UTF_8));
        assertTrue(
                config.setLastModified(config.lastModified() + 5000),
                "The test needs a different modification time.");
        GrugConfig.Values edited = GrugConfig.forMod("live");
        assertFalse(edited == values);
        assertEquals(9.0, edited.number("count"));
        assertTrue(edited.bool("on"), "An absent value is the declared default.");
        assertTrue(reports.isEmpty(), "A legal config reports nothing.");
    }

    @Test
    void aFileThatAppearsWhereNoneWasCachedIsReadAgain() throws Exception {
        File gameDir = tmp.resolve("created").toFile();
        File modsDir = new File(gameDir, "mods");
        assertTrue(modsDir.mkdirs());
        writeAbout(modsDir, "declares-none", "{}");
        installAdapter(gameDir, modsDir);

        assertFalse(GrugConfig.file(gameDir, "declares-none").exists());
        GrugConfig.Values absent = GrugConfig.forMod("declares-none");
        assertFalse(GrugConfig.file(gameDir, "declares-none").exists());

        File config = GrugConfig.file(gameDir, "declares-none");
        assertTrue(config.getParentFile().mkdirs());
        Files.write(config.toPath(), "on: false\n".getBytes(StandardCharsets.UTF_8));
        assertTrue(config.setLastModified(0), "The test needs an unchanged modification time.");
        assertFalse(
                GrugConfig.forMod("declares-none") == absent,
                "A file that appeared is read rather than answered from the absent-file cache.");
    }

    @Test
    void anUnreadableConfigReportsAndAnswersTheDefaults() throws Exception {
        File gameDir = tmp.resolve("broken").toFile();
        File modsDir = new File(gameDir, "mods");
        assertTrue(modsDir.mkdirs());
        writeAbout(modsDir, "broken", DECLARATION);
        installAdapter(gameDir, modsDir);
        File config = GrugConfig.file(gameDir, "broken");
        assertTrue(config.getParentFile().mkdirs());
        assertTrue(config.mkdirs(), "A directory where the file belongs cannot be read.");

        GrugConfig.Values values = GrugConfig.forMod("broken");

        assertEquals(4.0, values.number("count"));
        assertEquals(1, reports.size());
        assertTrue(reports.get(0).contains("cannot be read"), reports.get(0));
    }

    @Test
    void aChangedAboutJsonIsReadEvenWhenTheConfigFileIsUnchanged() throws Exception {
        File gameDir = tmp.resolve("metadata").toFile();
        File modsDir = new File(gameDir, "mods");
        assertTrue(modsDir.mkdirs());
        writeAbout(modsDir, "live", DECLARATION);
        installAdapter(gameDir, modsDir);

        GrugConfig.Values first = GrugConfig.forMod("live");
        File about = new File(new File(modsDir, "live"), "about.json");
        File config = GrugConfig.file(gameDir, "live");
        assertTrue(config.isFile(), "The first read writes the declared defaults.");
        long configModified = config.lastModified();

        String changed =
                "{\"config\": [{\"key\": \"rate\", \"type\": \"number\", \"title\": \"Rate\","
                        + " \"description\": \"How often.\", \"default\": 7}]}";
        Files.write(about.toPath(), changed.getBytes(StandardCharsets.UTF_8));
        assertTrue(
                about.setLastModified(about.lastModified() + 5000),
                "The test needs a different modification time for about.json.");
        assertEquals(configModified, config.lastModified(), "The config file itself is untouched.");

        GrugConfig.Values second = GrugConfig.forMod("live");
        assertFalse(second == first, "A changed about.json is not answered from the cache.");
        assertTrue(second.declares("rate"), "The changed declaration is the one resolved against.");
        assertFalse(second.declares("on"), "An option the new about.json dropped is gone.");
        assertEquals(7.0, second.number("rate"), "The new declaration's default is read.");
    }

    @Test
    void aWhiteFileValueIsTheColourRatherThanTheDeclaredDefault() throws Exception {
        File gameDir = tmp.resolve("white").toFile();
        File modsDir = new File(gameDir, "mods");
        assertTrue(modsDir.mkdirs());
        writeAbout(modsDir, "white", DECLARATION);
        installAdapter(gameDir, modsDir);
        File config = GrugConfig.file(gameDir, "white");
        assertTrue(config.getParentFile().mkdirs());
        Files.write(config.toPath(), "tint: #FFFFFF\n".getBytes(StandardCharsets.UTF_8));

        assertEquals(
                -1,
                GrugConfig.forMod("white").color("tint"),
                "Opaque white is a value, so the file's colour wins over the declared one.");
        assertTrue(reports.isEmpty(), "A legal config reports nothing.");
    }

    private static void writeAbout(File modsDir, String mod, String declaration)
            throws IOException {
        File dir = new File(modsDir, mod);
        assertTrue(dir.mkdirs());
        Files.write(
                new File(dir, "about.json").toPath(), declaration.getBytes(StandardCharsets.UTF_8));
    }

    private void installAdapter(File gameDir, File modsDir) throws Exception {
        Field field = GrugCore.class.getDeclaredField("adapter");
        field.setAccessible(true);
        savedAdapter = field.get(null);
        field.set(
                null,
                Proxy.newProxyInstance(
                        getClass().getClassLoader(),
                        new Class<?>[] {ModLoaderAdapter.class},
                        (proxy, method, args) -> {
                            if (method.isDefault()) {
                                return InvocationHandler.invokeDefault(proxy, method, args);
                            }
                            if (method.getName().equals("getGameDirectory")) return gameDir;
                            if (method.getName().equals("getGrugModsDirectory")) return modsDir;
                            if (method.getName().equals("logError")) return null;
                            throw new AssertionError("unexpected adapter call " + method.getName());
                        }));
    }
}
