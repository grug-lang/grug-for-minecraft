package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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

    /**
     * Whether {@link #installAdapter} saved an adapter that {@link #restoreAdapter} must put back.
     */
    private boolean adapterInstalled;

    @BeforeEach
    @AfterEach
    void clear() throws Exception {
        Field field = GrugMetadata.class.getDeclaredField("cache");
        field.setAccessible(true);
        ((java.util.Map<?, ?>) field.get(null)).clear();
    }

    @AfterEach
    void restoreAdapter() throws Exception {
        if (!adapterInstalled) return;
        Field field = GrugCore.class.getDeclaredField("adapter");
        field.setAccessible(true);
        field.set(null, savedAdapter);
        savedAdapter = null;
        adapterInstalled = false;
    }

    private static final String ABOUT =
            "{\"keys\": [{\"id\": \"toggle\", \"name\": \"Toggle SafeSquares\", \"name_key\":"
                    + " \"key.mod.toggle\", \"default_key\": \"l\"}, {\"id\": \"unbound\","
                    + " \"name\": \"Unbound\", \"name_key\": 7}, 7, {\"id\":"
                    + " \"no-key\"}, {\"name\": \"no-id\", \"default_key\":"
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

        assertEquals(2, mod.keys().size(), "The complete binding and the unbound one are kept.");
        GrugMetadata.Key key = mod.keys().get(0);
        assertEquals("toggle", key.id());
        assertEquals("Toggle SafeSquares", key.name());
        assertEquals("key.mod.toggle", key.nameKey());
        assertEquals('L', key.code());
        GrugMetadata.Key unbound = mod.keys().get(1);
        assertEquals("unbound", unbound.id());
        assertEquals("Unbound", unbound.name());
        assertNull(
                unbound.nameKey(), "A name_key that is not a text is dropped, as for every text.");
        assertEquals(
                GrugMetadata.UNBOUND_KEY,
                unbound.code(),
                "A binding with no default_key is kept with the unbound code, not dropped.");

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
                "A repeated name carries its mod and id, so both Controls rows read apart.");
        assertEquals(
                "key.grug.alpha.toggle",
                declared.get(0).savedName(),
                "The saved name names the declaring mod and key, not the deduplicated label.");
        assertEquals("key.grug.beta.toggle", declared.get(1).savedName());
    }

    @Test
    void anOldLabelsSavedRebindIsAppendedUnderTheDeclaringModAndKeyId() throws Exception {
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
        String saved =
                "music:1.0\n"
                        + "key_Toggle:30\n"
                        + "key_Toggle (beta.toggle):31\n"
                        + "key_Sprint:32\n";
        String migrated = GrugMetadata.migrateSavedKeys(saved, declared);
        assertEquals(
                "music:1.0\n"
                        + "key_Toggle:30\n"
                        + "key_Toggle (beta.toggle):31\n"
                        + "key_Sprint:32\n"
                        + "key_key.grug.alpha.toggle:30\n"
                        + "key_key.grug.beta.toggle:31\n",
                migrated,
                "Each old label gets a stable line appended after the file's own lines, which keep "
                        + "their bytes and their values.");
        assertEquals(
                migrated,
                GrugMetadata.migrateSavedKeys(migrated, declared),
                "A second start finds the stable lines and writes the same text, even with the old "
                        + "lines still there.");
    }

    @Test
    void anAlphaBindingWhoseNameMatchesALabelKeepsItsLine() throws Exception {
        File mod = mods.resolve("alpha").toFile();
        assertTrue(mod.mkdirs());
        Files.write(
                new File(mod, "about.json").toPath(),
                ("{\"keys\": [{\"id\": \"forward\", \"name\": \"Forward\","
                                + " \"default_key\": \"W\"}]}")
                        .getBytes(StandardCharsets.UTF_8));
        installAdapter(mods.toFile());

        java.util.List<GrugMetadata.DeclaredKey> declared = GrugMetadata.declaredKeys();
        String saved = "key_Forward:30\n";
        String migrated = GrugMetadata.migrateSavedKeys(saved, declared);
        assertEquals(
                "key_Forward:30\nkey_key.grug.alpha.forward:30\n",
                migrated,
                "The game's own Forward binding keeps its line, and the declared binding's rebind "
                        + "is appended under its stable name.");
        assertEquals(
                migrated,
                GrugMetadata.migrateSavedKeys(migrated, declared),
                "The second start finds the stable line, so the old line is left and nothing is "
                        + "appended again.");
        assertEquals(
                "key_Forward:30\nkey_key.grug.alpha.forward:30",
                GrugMetadata.migrateSavedKeys("key_Forward:30", declared),
                "A file with no trailing newline gains one before the appended line.");
    }

    @Test
    void anOldLabelLineDoesNotOverrideAStableSavedLine() throws Exception {
        File mod = mods.resolve("only").toFile();
        assertTrue(mod.mkdirs());
        Files.write(
                new File(mod, "about.json").toPath(),
                "{\"keys\": [{\"id\": \"toggle\", \"name\": \"Toggle\", \"default_key\": \"K\"}]}"
                        .getBytes(StandardCharsets.UTF_8));
        installAdapter(mods.toFile());

        java.util.List<GrugMetadata.DeclaredKey> declared = GrugMetadata.declaredKeys();
        String saved = "key_Toggle:30\r\nkey_key.grug.only.toggle:40\r\n";
        assertEquals(
                saved,
                GrugMetadata.migrateSavedKeys(saved, declared),
                "The stable line is already in the file, so the old line is left as whatever "
                        + "binding owns it and nothing is appended or dropped.");
    }

    @Test
    void aDuplicatedOldLabelMigratesTheLastLinesValue() throws Exception {
        File mod = mods.resolve("only").toFile();
        assertTrue(mod.mkdirs());
        Files.write(
                new File(mod, "about.json").toPath(),
                "{\"keys\": [{\"id\": \"toggle\", \"name\": \"Toggle\", \"default_key\": \"K\"}]}"
                        .getBytes(StandardCharsets.UTF_8));
        installAdapter(mods.toFile());

        java.util.List<GrugMetadata.DeclaredKey> declared = GrugMetadata.declaredKeys();
        String saved = "key_Toggle:30\nkey_Toggle:31\n";
        assertEquals(
                "key_Toggle:30\nkey_Toggle:31\nkey_key.grug.only.toggle:31\n",
                GrugMetadata.migrateSavedKeys(saved, declared),
                "The game reads a name's last line, so a duplicated old label migrates the value "
                        + "the game was using, not the first one.");
    }

    @Test
    void anOldLabelThatTextuallyEqualsAnotherBindingsStableNameStillAppendsBoth() throws Exception {
        File alpha = mods.resolve("alpha").toFile();
        File beta = mods.resolve("beta").toFile();
        assertTrue(alpha.mkdirs());
        assertTrue(beta.mkdirs());
        Files.write(
                new File(alpha, "about.json").toPath(),
                "{\"keys\": [{\"id\": \"x\", \"name\": \"Toggle\", \"default_key\": \"K\"}]}"
                        .getBytes(StandardCharsets.UTF_8));
        Files.write(
                new File(beta, "about.json").toPath(),
                ("{\"keys\": [{\"id\": \"y\", \"name\": \"key.grug.alpha.x\","
                                + " \"default_key\": \"L\"}]}")
                        .getBytes(StandardCharsets.UTF_8));
        installAdapter(mods.toFile());

        java.util.List<GrugMetadata.DeclaredKey> declared = GrugMetadata.declaredKeys();
        String saved = "key_Toggle:30\nkey_key.grug.alpha.x:31\n";
        assertEquals(
                "key_Toggle:30\n"
                        + "key_key.grug.alpha.x:31\n"
                        + "key_key.grug.alpha.x:30\n"
                        + "key_key.grug.beta.y:31\n",
                GrugMetadata.migrateSavedKeys(saved, declared),
                "beta's old label is alpha's stable name, so that line is an old label rather "
                        + "than alpha's saved line and neither append may be skipped.");
    }

    @Test
    void anOptionsFileWithBytesThePlatformCharsetCannotDecodeKeepsThem() throws Exception {
        File mod = mods.resolve("only").toFile();
        assertTrue(mod.mkdirs());
        Files.write(
                new File(mod, "about.json").toPath(),
                "{\"keys\": [{\"id\": \"toggle\", \"name\": \"Toggle\", \"default_key\": \"K\"}]}"
                        .getBytes(StandardCharsets.UTF_8));
        installAdapter(mods.toFile());
        File options = mods.resolve("options.txt").toFile();
        // 0xE9 alone is not valid UTF-8, which is the platform charset on most CI runners.
        byte[] saved =
                "music:1.0\nname:caf\u00e9\nkey_Toggle:30\n".getBytes(StandardCharsets.ISO_8859_1);
        Files.write(options.toPath(), saved);

        GrugMetadata.migrateSavedKeys(options, GrugMetadata.declaredKeys());

        byte[] expected =
                "music:1.0\nname:caf\u00e9\nkey_Toggle:30\nkey_key.grug.only.toggle:30\n"
                        .getBytes(StandardCharsets.ISO_8859_1);
        assertArrayEquals(
                expected,
                Files.readAllBytes(options.toPath()),
                "0xE9 is not valid UTF-8, so ISO-8859-1 keeps that byte while the stable line is "
                        + "appended.");
    }

    @Test
    void anOptionsFileOnDiskMigratesOnce() throws Exception {
        File mod = mods.resolve("only").toFile();
        assertTrue(mod.mkdirs());
        Files.write(
                new File(mod, "about.json").toPath(),
                "{\"keys\": [{\"id\": \"toggle\", \"name\": \"Toggle\", \"default_key\": \"K\"}]}"
                        .getBytes(StandardCharsets.UTF_8));
        installAdapter(mods.toFile());
        File options = mods.resolve("options.txt").toFile();
        Files.write(
                options.toPath(), "music:1.0\nkey_Toggle:30\n".getBytes(StandardCharsets.UTF_8));

        GrugMetadata.migrateSavedKeys(options, GrugMetadata.declaredKeys());

        assertEquals(
                "music:1.0\nkey_Toggle:30\nkey_key.grug.only.toggle:30\n",
                new String(Files.readAllBytes(options.toPath()), StandardCharsets.UTF_8),
                "The old line stays and the stable line is appended after it.");

        assertTrue(options.setLastModified(1000L));
        GrugMetadata.migrateSavedKeys(options, GrugMetadata.declaredKeys());
        assertEquals(
                1000L,
                options.lastModified(),
                "A start that finds the stable line does not write the file.");
    }

    @Test
    void aCrlfOptionsFileKeepsItsLineEndingsWhenTheStableLineIsAppended() throws Exception {
        File mod = mods.resolve("only").toFile();
        assertTrue(mod.mkdirs());
        Files.write(
                new File(mod, "about.json").toPath(),
                "{\"keys\": [{\"id\": \"toggle\", \"name\": \"Toggle\", \"default_key\": \"K\"}]}"
                        .getBytes(StandardCharsets.UTF_8));
        installAdapter(mods.toFile());

        java.util.List<GrugMetadata.DeclaredKey> declared = GrugMetadata.declaredKeys();
        String saved = "music:1.0\r\nkey_Toggle:30\r\n";
        assertEquals(
                "music:1.0\r\nkey_Toggle:30\r\nkey_key.grug.only.toggle:30\r\n",
                GrugMetadata.migrateSavedKeys(saved, declared),
                "The appended line uses the file's own line ending, and every existing byte is "
                        + "kept.");
    }

    @Test
    void aMissingOptionsFileIsLeftForTheGameToCreate() throws Exception {
        File mod = mods.resolve("only").toFile();
        assertTrue(mod.mkdirs());
        Files.write(
                new File(mod, "about.json").toPath(),
                "{\"keys\": [{\"id\": \"toggle\", \"name\": \"Toggle\", \"default_key\": \"K\"}]}"
                        .getBytes(StandardCharsets.UTF_8));
        installAdapter(mods.toFile());
        File options = mods.resolve("options.txt").toFile();

        GrugMetadata.migrateSavedKeys(options, GrugMetadata.declaredKeys());

        assertFalse(
                options.exists(),
                "The first start has no options file yet, so the migration has nothing to do and "
                        + "does not create one.");
        assertEquals(
                "key_Toggle:30\n",
                GrugMetadata.migrateSavedKeys("key_Toggle:30\n", java.util.Collections.emptyList()),
                "No declared binding means no label to migrate.");
        assertEquals(
                "",
                GrugMetadata.migrateSavedKeys("", GrugMetadata.declaredKeys()),
                "An empty options file has no old line and is returned as it is.");

        File noBindings = mods.resolve("options.txt").toFile();
        Files.write(noBindings.toPath(), "key_Toggle:30\n".getBytes(StandardCharsets.UTF_8));
        GrugMetadata.migrateSavedKeys(
                noBindings, java.util.Collections.<GrugMetadata.DeclaredKey>emptyList());
        assertEquals(
                "key_Toggle:30\n",
                new String(Files.readAllBytes(noBindings.toPath()), StandardCharsets.UTF_8),
                "An options file is not touched when no mod declares a binding.");
    }

    @Test
    void aLabelThatIsAlreadyTheStableSavedNameIsLeftAlone() throws Exception {
        File mod = mods.resolve("odd").toFile();
        assertTrue(mod.mkdirs());
        Files.write(
                new File(mod, "about.json").toPath(),
                ("{\"keys\": [{\"id\": \"toggle\", \"name\": \"key.grug.odd.toggle\","
                                + " \"default_key\": \"K\"}]}")
                        .getBytes(StandardCharsets.UTF_8));
        installAdapter(mods.toFile());

        java.util.List<GrugMetadata.DeclaredKey> declared = GrugMetadata.declaredKeys();
        assertEquals("key.grug.odd.toggle", declared.get(0).label());
        assertEquals(
                "key_key.grug.odd.toggle:30\n",
                GrugMetadata.migrateSavedKeys("key_key.grug.odd.toggle:30\n", declared),
                "A label that is already the stable name is not a line to rewrite.");
    }

    @Test
    void aPathThatCannotBeReadAsAnOptionsFileLeavesTheGameItsOwnRead() throws Exception {
        File mod = mods.resolve("only").toFile();
        assertTrue(mod.mkdirs());
        Files.write(
                new File(mod, "about.json").toPath(),
                "{\"keys\": [{\"id\": \"toggle\", \"name\": \"Toggle\", \"default_key\": \"K\"}]}"
                        .getBytes(StandardCharsets.UTF_8));
        installAdapter(mods.toFile());
        // A directory where the options file should be cannot be read, and the migration must not
        // stop the game over it: the game reports the file itself.
        File options = mods.resolve("options.txt").toFile();
        assertTrue(options.mkdirs());
        assertTrue(new File(options, "kept").createNewFile());

        GrugMetadata.migrateSavedKeys(options, GrugMetadata.declaredKeys());

        assertTrue(
                new File(options, "kept").isFile(),
                "A path that is not a file is left exactly as it was.");
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
        adapterInstalled = true;
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
    void anAdapterThatWasNullBeforeTheInstallIsPutBackAsNull() throws Exception {
        Field field = GrugCore.class.getDeclaredField("adapter");
        field.setAccessible(true);
        Object real = field.get(null);
        field.set(null, null);
        try {
            installAdapter(mods.toFile());
            assertNotNull(GrugCore.getAdapter(), "The test's proxy is installed.");
            restoreAdapter();
            assertNull(
                    GrugCore.getAdapter(),
                    "A saved adapter of null is restored, rather than the proxy left in place.");
        } finally {
            field.set(null, real);
            savedAdapter = null;
            adapterInstalled = false;
        }
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
