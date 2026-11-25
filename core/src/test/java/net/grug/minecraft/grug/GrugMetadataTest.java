package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.grug.minecraft.core.GrugCore;
import net.grug.minecraft.core.ModLoaderAdapter;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Covers what the host reads from a mod's {@code about.json}: the bindings it declares, the
 * configuration options it declares, the colour and key text shapes, and the cache that makes a
 * rewrite visible without a restart.
 */
class GrugMetadataTest {

    @TempDir Path mods;

    private Object savedAdapter;

    @BeforeEach
    @AfterEach
    void clear() throws Exception {
        Field field = GrugMetadata.class.getDeclaredField("cache");
        field.setAccessible(true);
        ((java.util.Map<?, ?>) field.get(null)).clear();
    }

    @AfterEach
    void restoreAdapter() throws Exception {
        if (savedAdapter == null) return;
        Field field = GrugCore.class.getDeclaredField("adapter");
        field.setAccessible(true);
        field.set(null, savedAdapter);
        savedAdapter = null;
    }

    private static final String ABOUT =
            "{\"keys\": [{\"id\": \"toggle\", \"name\": \"Toggle SafeSquares\", \"default_key\":"
                    + " \"l\"}, 7, {\"id\": \"no-key\"}, {\"name\": \"no-id\", \"default_key\":"
                    + " \"L\"}, {\"id\": \"too-long\", \"name\": \"x\", \"default_key\":"
                    + " \"LL\"}], \"config\": [{\"key\": \"on\", \"type\": \"boolean\","
                    + " \"title\": \"On\", \"description\": \"Whether.\", \"default\": true},"
                    + " {\"key\": \"count\", \"type\": \"number\", \"title\": \"Count\","
                    + " \"description\": \"How many.\", \"default\": 2.5, \"min\": 1,"
                    + " \"max\": 9}, {\"key\": \"tint\", \"type\": \"color\","
                    + " \"title\": \"Tint\", \"description\": \"Which.\","
                    + " \"default\": \"#40112233\"}, {\"key\": \"bad-color\","
                    + " \"type\": \"color\", \"title\": \"Bad\", \"description\": \"Bad.\","
                    + " \"default\": \"blue\"}, {\"key\": \"bad-type\", \"type\": \"text\","
                    + " \"title\": \"Bad\", \"description\": \"Bad.\", \"default\": \"x\"},"
                    + " {\"key\": \"no-default\", \"type\": \"boolean\", \"title\": \"No\","
                    + " \"description\": \"No.\"}, {\"key\": \"bad-default\","
                    + " \"type\": \"boolean\", \"title\": \"Bad\", \"description\":"
                    + " \"Bad.\", \"default\": [], \"min\": {}}, {\"key\": \"bad-range\","
                    + " \"type\": \"number\", \"title\": \"Bad\", \"description\":"
                    + " \"Bad.\", \"default\": 1, \"max\": \"x\"}, {\"type\": \"boolean\","
                    + " \"title\": \"T\", \"description\": \"D\", \"default\": true},"
                    + " {\"key\": \"k\", \"title\": \"T\", \"description\": \"D\","
                    + " \"default\": true}, {\"key\": \"k\", \"type\": \"boolean\","
                    + " \"description\": \"D\", \"default\": true}, {\"key\": \"k\","
                    + " \"type\": \"boolean\", \"title\": \"T\", \"default\": true},"
                    + " {\"key\": \"k\", \"type\": \"boolean\", \"title\": [],"
                    + " \"description\": \"D\", \"default\": true}, {\"key\": \"k\","
                    + " \"type\": \"boolean\", \"title\": 5, \"description\": \"D\","
                    + " \"default\": true}, \"not-an-object\"]}";

    @Test
    void aDeclarationIsReadIntoBindingsAndOptions() {
        GrugMetadata.Mod mod = GrugMetadata.read(ABOUT);

        assertEquals(1, mod.keys().size(), "Only the one complete binding is kept.");
        GrugMetadata.Key key = mod.keys().get(0);
        assertEquals("toggle", key.id());
        assertEquals("Toggle SafeSquares", key.name());
        assertEquals('L', key.code());

        assertEquals(5, mod.options().size(), "The five well-typed options are kept.");
        GrugMetadata.Option on = mod.option("on");
        assertNotNull(on);
        assertEquals("boolean", on.type());
        assertTrue(on.defaultBool());
        assertEquals("On", on.title());
        assertEquals("Whether.", on.description());
        assertFalse(on.hasMin());
        assertFalse(on.hasMax());
        assertTrue(on.inRange(-2), "An option with no range accepts any number.");
        assertNull(mod.option("nope"));
        assertEquals(0, mod.options().indexOf(on), "The options keep their declared order.");
    }

    @Test
    void aNumberKeepsItsRangeAndAColourItsAlpha() {
        GrugMetadata.Mod mod = GrugMetadata.read(ABOUT);

        GrugMetadata.Option count = mod.option("count");
        assertEquals(2.5, count.defaultNumber());
        assertTrue(count.hasMin());
        assertTrue(count.hasMax());
        assertEquals(1.0, count.min());
        assertEquals(9.0, count.max());
        assertFalse(count.inRange(0.5));
        assertFalse(count.inRange(9.5));
        assertTrue(count.inRange(4.5));

        GrugMetadata.Option tint = mod.option("tint");
        assertEquals(0x40112233, tint.defaultColor());
        assertEquals(0, tint.defaultNumber(), "A colour has no number default.");
        assertFalse(tint.defaultBool(), "A colour has no boolean default.");
    }

    @Test
    void aDeclarationTheHostCannotReadIsNoneRatherThanACrash() throws Exception {
        File about = mods.resolve("mod/about.json").toFile();
        about.getParentFile().mkdirs();
        Files.write(about.toPath(), "{not json".getBytes(StandardCharsets.UTF_8));
        assertTrue(GrugMetadata.read(about).keys().isEmpty());
        assertTrue(GrugMetadata.read(about).options().isEmpty());

        assertTrue(GrugMetadata.read(mods.resolve("none/about.json").toFile()).keys().isEmpty());
        assertTrue(
                GrugMetadata.read(mods.toFile()).keys().isEmpty(),
                "A directory is not a file to read.");
        assertTrue(GrugMetadata.read("{}").keys().isEmpty());
        assertTrue(GrugMetadata.read("{\"keys\": 1, \"config\": 2}").keys().isEmpty());
        assertThrows(
                RuntimeException.class,
                () -> GrugMetadata.read("[1]"),
                "Malformed JSON is the caller's to catch.");
    }

    @Test
    void aModIsReadOnceAndAgainWhenItsFileChanges() throws Exception {
        File dir = mods.resolve("mod").toFile();
        assertTrue(dir.mkdirs());
        File about = new File(dir, "about.json");
        Files.write(about.toPath(), "{\"keys\": []}".getBytes(StandardCharsets.UTF_8));
        long first = about.lastModified();

        GrugMetadata.Mod empty = GrugMetadata.forMod(mods.toFile(), "mod");
        assertTrue(empty.keys().isEmpty());
        assertTrue(
                GrugMetadata.forMod(mods.toFile(), "mod") == empty, "The second read is cached.");

        Files.write(
                about.toPath(),
                "{\"keys\": [{\"id\": \"a\", \"name\": \"A\", \"default_key\": \"a\"}]}"
                        .getBytes(StandardCharsets.UTF_8));
        assertTrue(
                about.setLastModified(first + 5000),
                "The test needs a different modification time.");
        GrugMetadata.Mod changed = GrugMetadata.forMod(mods.toFile(), "mod");
        assertFalse(changed == empty);
        assertEquals("a", changed.keys().get(0).id());

        assertTrue(
                GrugMetadata.forMod(mods.toFile(), "absent").keys().isEmpty(),
                "A mod with no about.json declares nothing.");
    }

    @Test
    void theRunningGameNamesTheModsDirectory() throws Exception {
        File dir = mods.resolve("caller").toFile();
        assertTrue(dir.mkdirs());
        Files.write(
                new File(dir, "about.json").toPath(),
                ("{\"config\": [{\"key\": \"k\", \"type\": \"number\", \"title\": \"K\","
                                + " \"description\": \"K.\", \"default\": 3}]}")
                        .getBytes(StandardCharsets.UTF_8));
        installAdapter(mods.toFile());

        assertEquals(1, GrugMetadata.forCallerMod("caller").options().size());
    }

    @Test
    void everyMountedModsDeclaredKeysAreListedInLoadOrder() throws Exception {
        File beta = mods.resolve("beta").toFile();
        File alpha = mods.resolve("alpha").toFile();
        assertTrue(beta.mkdirs());
        assertTrue(alpha.mkdirs());
        Files.write(
                new File(beta, "about.json").toPath(),
                ("{\"keys\": [{\"id\": \"second\", \"name\": \"Second\", \"default_key\":"
                                + " \"K\"}, {\"id\": \"first\", \"name\": \"First\","
                                + " \"default_key\": \"L\"}]}")
                        .getBytes(StandardCharsets.UTF_8));
        Files.write(
                new File(alpha, "about.json").toPath(),
                "{\"keys\": [{\"id\": \"only\", \"name\": \"Only\", \"default_key\": \"O\"}]}"
                        .getBytes(StandardCharsets.UTF_8));
        assertTrue(new File(mods.toFile(), "notes.txt").createNewFile());
        installAdapter(mods.toFile());

        java.util.List<GrugMetadata.DeclaredKey> declared = GrugMetadata.declaredKeys();
        assertEquals(3, declared.size(), "One mod file is not a mod and declares nothing.");
        assertEquals("alpha", declared.get(0).mod());
        assertEquals("only", declared.get(0).key().id());
        assertEquals("beta", declared.get(1).mod());
        assertEquals("first", declared.get(1).key().id());
        assertEquals("beta", declared.get(2).mod());
        assertEquals("second", declared.get(2).key().id());
    }

    @Test
    void bindingsThatShareADisplayNameKeepALabelOfTheirOwn() throws Exception {
        File beta = mods.resolve("beta").toFile();
        File alpha = mods.resolve("alpha").toFile();
        assertTrue(beta.mkdirs());
        assertTrue(alpha.mkdirs());
        Files.write(
                new File(beta, "about.json").toPath(),
                "{\"keys\": [{\"id\": \"toggle\", \"name\": \"Toggle\", \"default_key\": \"K\"}]}"
                        .getBytes(StandardCharsets.UTF_8));
        Files.write(
                new File(alpha, "about.json").toPath(),
                "{\"keys\": [{\"id\": \"toggle\", \"name\": \"Toggle\", \"default_key\": \"L\"}]}"
                        .getBytes(StandardCharsets.UTF_8));
        installAdapter(mods.toFile());

        java.util.List<GrugMetadata.DeclaredKey> declared = GrugMetadata.declaredKeys();
        assertEquals(2, declared.size());
        assertEquals("Toggle", declared.get(0).label(), "The first binding keeps the plain name.");
        assertEquals(
                "Toggle (beta.toggle)",
                declared.get(1).label(),
                "A repeated name carries its mod and id, because the loaders that save a binding "
                        + "under its label would otherwise keep both under one saved key.");
    }

    @Test
    void aModsDirectoryThatCannotBeListedDeclaresNoKeys() throws Exception {
        installAdapter(new File(mods.toFile(), "absent"));

        assertTrue(
                GrugMetadata.declaredKeys().isEmpty(),
                "A mods directory that is not there declares no key.");
    }

    private void installAdapter(File grugMods) throws Exception {
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
                            if (method.getName().equals("getGrugModsDirectory")) return grugMods;
                            if (method.getName().equals("logError")) return null;
                            throw new AssertionError("unexpected adapter call " + method.getName());
                        }));
    }

    @Test
    void theColourTextAcceptsTheShapesTheReferenceDoes() {
        assertEquals(0xFF112233L, GrugMetadata.colorCode("#112233"));
        assertEquals(0xFF112233L, GrugMetadata.colorCode(" 112233 "));
        assertEquals(0x80112233L, GrugMetadata.colorCode("#80112233"));
        assertEquals(
                0xFFFFFFFFL,
                GrugMetadata.colorCode("#FFFFFF"),
                "Opaque white is a value, so it cannot be the invalid colour.");
        assertEquals(0xFFFFFFFFL, GrugMetadata.colorCode("#FFFFFFFF"));
        assertEquals(GrugMetadata.INVALID_COLOR, GrugMetadata.colorCode(null));
        assertEquals(GrugMetadata.INVALID_COLOR, GrugMetadata.colorCode("#12345"));
        assertEquals(GrugMetadata.INVALID_COLOR, GrugMetadata.colorCode("#GGGGGG"));
        assertEquals(GrugMetadata.INVALID_COLOR, GrugMetadata.colorCode(""));

        assertEquals("#112233", GrugMetadata.colorText(0xFF112233));
        assertEquals("#80112233", GrugMetadata.colorText(0x80112233));
    }

    @Test
    void aKeyIsOneLetterOrDigit() {
        assertEquals('L', GrugMetadata.keyCode("l"));
        assertEquals('7', GrugMetadata.keyCode("7"));
        assertEquals(-1, GrugMetadata.keyCode(null));
        assertEquals(-1, GrugMetadata.keyCode(""));
        assertEquals(-1, GrugMetadata.keyCode("LL"));
        assertEquals(-1, GrugMetadata.keyCode("-"));
        assertEquals(-1, GrugMetadata.keyCode("["));
    }

    @Test
    void theDefaultIsWrittenInTheShapeTheConfigFileUses() {
        assertEquals("true", option("boolean", "true", null, null, null).defaultTextLine());
        assertEquals("false", option("boolean", "false", null, null, null).defaultTextLine());
        assertEquals("3", option("number", 3.0, null, null, null).defaultTextLine());
        assertEquals("2.5", option("number", 2.5, null, null, null).defaultTextLine());
        assertEquals("#112233", option("color", null, "#112233", null, null).defaultTextLine());
        assertEquals(
                Double.toString(1e20),
                option("number", 1e20, null, null, null).defaultTextLine(),
                "A whole number beyond long's range keeps the double's own text.");
        assertEquals(
                Double.toString(-1e20),
                option("number", -1e20, null, null, null).defaultTextLine(),
                "The same holds below long's range.");
        assertEquals(
                "Infinity",
                option("number", "1e999", null, null, null).defaultTextLine(),
                "A value JSON reads as infinite is written as it is.");
    }

    @Test
    void aDeclaredWhiteIsAColourAndADeclarationWithoutADefaultIsNotAnOption() {
        GrugMetadata.Option white = option("color", null, "#FFFFFF", null, null);
        assertEquals(
                -1,
                white.defaultColor(),
                "Opaque white fills the int, which is not the long sentinel any more.");
        assertEquals("#FFFFFF", white.defaultTextLine());

        GrugMetadata.Option opaqueWhite = option("color", null, "#FFFFFFFF", null, null);
        assertEquals(-1, opaqueWhite.defaultColor());
        assertEquals("#FFFFFF", opaqueWhite.defaultTextLine());

        GrugMetadata.Mod mod =
                GrugMetadata.read(
                        "{\"config\": [{\"key\": \"k\", \"type\": \"color\", \"title\": \"K\","
                                + " \"description\": \"K.\"}]}");
        assertTrue(
                mod.options().isEmpty(),
                "A colour with no default is not an option, however usable its type is.");
    }

    private static GrugMetadata.Option option(
            String type, Object value, String text, Double min, Double max) {
        String literal;
        if (value == null) {
            literal = "\"" + text + "\"";
        } else {
            literal = value.toString();
        }
        String range =
                (min == null ? "" : ", \"min\": " + min) + (max == null ? "" : ", \"max\": " + max);
        String json =
                "{\"config\": [{\"key\": \"k\", \"type\": \""
                        + type
                        + "\", \"title\": \"K\", \"description\": \"K.\", \"default\": "
                        + literal
                        + range
                        + "}]}";
        GrugMetadata.Mod mod = GrugMetadata.read(json);
        assertEquals(1, mod.options().size(), "The test's own declaration has to be valid.");
        return mod.options().get(0);
    }
}
