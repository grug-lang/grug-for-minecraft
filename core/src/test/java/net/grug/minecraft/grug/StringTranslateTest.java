package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import java.util.ArrayList;
import java.util.List;

/**
 * Covers the host side of {@code String.translate}: the selected language's file, the fallback to
 * en_us, a key in neither, the two refusal and failure paths a game run cannot reach, and the
 * per-dispatch running mod those entries read.
 *
 * <p>Every test uses its own mod name, because the parsed files are cached by mod and language for
 * the life of the process, the way a screen reads them on every frame.
 */
class StringTranslateTest {

    @TempDir Path tmp;

    private File mods;
    private File game;
    private Field adapterField;
    private Object savedAdapter;
    private final List<String> reported = new ArrayList<>();
    private String language = "en_us";

    @BeforeEach
    void installAnAdapterWithTheTwoDirectories() throws Exception {
        mods = Files.createDirectories(tmp.resolve("mods")).toFile();
        game = Files.createDirectories(tmp.resolve("game")).toFile();
        GrugTestState.testRunActive = false;
        synchronized (GrugErrors.runtimeErrorQueue) {
            GrugErrors.runtimeErrorQueue.clear();
        }
        reported.clear();
        adapterField = GrugCore.class.getDeclaredField("adapter");
        adapterField.setAccessible(true);
        savedAdapter = adapterField.get(null);
        adapterField.set(null, adapter());
    }

    @AfterEach
    void removeTheAdapterAndTheRunningMod() throws Exception {
        adapterField.set(null, savedAdapter);
        GrugScriptDispatch.end(null);
    }

    private Object adapter() {
        return Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {ModLoaderAdapter.class},
                (proxy, method, args) -> {
                    // The name is read before isDefault, because languageCode is a default method
                    // this adapter answers itself.
                    switch (method.getName()) {
                        case "getGrugModsDirectory":
                            return mods;
                        case "getGameDirectory":
                            return game;
                        case "languageCode":
                            return language;
                        case "logError":
                            reported.add((String) args[0]);
                            return null;
                        default:
                            if (method.isDefault()) {
                                return InvocationHandler.invokeDefault(proxy, method, args);
                            }
                            throw new AssertionError("unexpected adapter call " + method.getName());
                    }
                });
    }

    private void shipped(String mod, String path, String text) throws Exception {
        Path file = mods.toPath().resolve(mod).resolve("files").resolve(path);
        Files.createDirectories(file.getParent());
        Files.write(file, text.getBytes(StandardCharsets.UTF_8));
    }

    private void writable(String mod, String path, String text) throws Exception {
        Path file =
                game.toPath().resolve("grug_mod_files").resolve(mod).resolve("files").resolve(path);
        Files.createDirectories(file.getParent());
        Files.write(file, text.getBytes(StandardCharsets.UTF_8));
    }

    private static String translate(String mod, String key) {
        String previous = GrugScriptDispatch.begin(mod);
        try {
            return HostFunctions.String_translate(key);
        } finally {
            GrugScriptDispatch.end(previous);
        }
    }

    private static String translateWith(String mod, String key, String value) {
        String previous = GrugScriptDispatch.begin(mod);
        try {
            return HostFunctions.String_translate_with(key, value);
        } finally {
            GrugScriptDispatch.end(previous);
        }
    }

    @Test
    void theSelectedLanguagesTextIsAnswered() throws Exception {
        shipped("known", "lang/en_us.json", "{\"a\": \"A\"}");
        shipped("known", "lang/zh_cn.json", "{\"a\": \"甲\"}");
        language = "zh_cn";

        assertEquals("甲", translate("known", "a"), "The selected language's text should answer.");
        // The second call reads the cached tables, which is the path a screen's every frame takes.
        assertEquals("甲", translate("known", "a"), "The cached text should answer the same.");
    }

    @Test
    void aWritableFileWinsOverTheShippedOne() throws Exception {
        shipped("overridden", "lang/en_us.json", "{\"a\": \"shipped\"}");
        writable("overridden", "lang/en_us.json", "{\"a\": \"written\"}");

        assertEquals("written", translate("overridden", "a"));
    }

    @Test
    void aKeyTheSelectedLanguageLacksFallsBackToEnUs() throws Exception {
        shipped("fallback", "lang/en_us.json", "{\"a\": \"A\"}");
        shipped("fallback", "lang/zh_cn.json", "{\"b\": \"乙\"}");
        language = "zh_cn";

        assertEquals("A", translate("fallback", "a"), "en_us should answer a key zh_cn lacks.");
        assertEquals("乙", translate("fallback", "b"), "zh_cn's own key should still answer.");
    }

    @Test
    void anAbsentLanguageFileFallsBackToEnUs() throws Exception {
        shipped("absent", "lang/en_us.json", "{\"a\": \"A\"}");
        language = "fr_fr";

        assertEquals("A", translate("absent", "a"));
    }

    @Test
    void aKeyInNeitherFileAnswersTheKey() throws Exception {
        shipped("neither", "lang/en_us.json", "{\"a\": \"A\"}");

        assertEquals("b", translate("neither", "b"));
    }

    @Test
    void aModWithNoLanguageFileAtAllAnswersTheKey() throws Exception {
        // No file is shipped, so the selected language and the fallback are both absent.
        assertEquals("a", translate("nofile", "a"));
    }

    @Test
    void aBrokenSelectedFileIsReportedAndFallsBack() throws Exception {
        shipped("broken", "lang/en_us.json", "{\"a\": \"A\"}");
        shipped("broken", "lang/zh_cn.json", "not json");
        language = "zh_cn";

        assertEquals("A", translate("broken", "a"));
        assertTrue(
                reported.stream().anyMatch(text -> text.contains("lang/zh_cn.json")),
                "The broken file should be reported: " + reported);

        // A file that is fixed answers its new text on the next call, because a broken read is not
        // cached.
        shipped("broken", "lang/zh_cn.json", "{\"a\": \"乙\"}");
        assertEquals("乙", translate("broken", "a"));
    }

    @Test
    void aBrokenFallbackFileStillAnswersTheSelectedLanguage() throws Exception {
        shipped("brokenfallback", "lang/en_us.json", "not json");
        shipped("brokenfallback", "lang/zh_cn.json", "{\"a\": \"甲\"}");
        language = "zh_cn";

        assertEquals("甲", translate("brokenfallback", "a"));
        assertTrue(
                reported.stream().anyMatch(text -> text.contains("lang/en_us.json")),
                "The broken fallback should be reported: " + reported);
    }

    @Test
    void aNestedValueIsNotTextAndDoesNotHideTheStringsBesideIt() throws Exception {
        // A value that is not a string is skipped, and one that is not a primitive at all short
        // circuits the check, while the strings beside them still answer.
        shipped(
                "nested",
                "lang/en_us.json",
                "{\"a\": \"A\", \"b\": 1, \"c\": {\"d\": \"D\"}, \"e\": \"E\"}");

        assertEquals("A", translate("nested", "a"));
        assertEquals("b", translate("nested", "b"), "A number should answer its key.");
        assertEquals("c", translate("nested", "c"), "An object should answer its key.");
        assertEquals("E", translate("nested", "e"));
    }

    @Test
    void aLoaderThatNamesNoLanguageReadsEnUs() throws Exception {
        // The interface's own answer, which a loader that has no language to name inherits.
        shipped("defaultlang", "lang/en_us.json", "{\"a\": \"A\"}");
        adapterField.set(
                null,
                Proxy.newProxyInstance(
                        getClass().getClassLoader(),
                        new Class<?>[] {ModLoaderAdapter.class},
                        (proxy, method, args) -> {
                            switch (method.getName()) {
                                case "getGrugModsDirectory":
                                    return mods;
                                case "getGameDirectory":
                                    return game;
                                case "logError":
                                    reported.add((String) args[0]);
                                    return null;
                                default:
                                    if (method.isDefault()) {
                                        return InvocationHandler.invokeDefault(proxy, method, args);
                                    }
                                    throw new AssertionError(
                                            "unexpected adapter call " + method.getName());
                            }
                        }));

        assertEquals("en_us", ((ModLoaderAdapter) adapterField.get(null)).languageCode());
        assertEquals("A", translate("defaultlang", "a"));
    }

    @Test
    void aLanguageFileThatIsNotAnObjectIsReported() throws Exception {
        shipped("array", "lang/en_us.json", "[\"a\"]");

        assertEquals("a", translate("array", "a"));
        assertTrue(
                reported.stream().anyMatch(text -> text.contains("not a JSON object")),
                "A non-object file should be reported: " + reported);
    }

    @Test
    void aDirectoryWhereTheFileBelongsIsReported() throws Exception {
        Path file =
                mods.toPath()
                        .resolve("directory")
                        .resolve("files")
                        .resolve("lang")
                        .resolve("en_us.json");
        Files.createDirectories(file);

        assertEquals("a", translate("directory", "a"));
        assertTrue(
                reported.stream().anyMatch(text -> text.contains("names a directory")),
                "A directory should be reported: " + reported);
    }

    @Test
    void aFileThatCannotBeReadIsReported() throws Exception {
        shipped("unreadable", "lang/en_us.json", "{\"a\": \"A\"}");
        File file =
                mods.toPath()
                        .resolve("unreadable")
                        .resolve("files")
                        .resolve("lang")
                        .resolve("en_us.json")
                        .toFile();
        // A file the process cannot read, which a root run ignores, so the arm below is skipped
        // there rather than asserted on a read that went through.
        file.setReadable(false, true);
        if (file.canRead()) return;
        try {
            assertEquals("a", translate("unreadable", "a"));
        } finally {
            file.setReadable(true, true);
        }
        assertTrue(
                reported.stream().anyMatch(text -> text.contains("lang/en_us.json")),
                "An unreadable file should be reported: " + reported);
    }

    @Test
    void aCallWithNoRunningModIsRefused() {
        GrugScriptDispatch.begin(null);

        assertEquals("a", HostFunctions.String_translate("a"));
        assertTrue(
                reported.stream()
                        .anyMatch(
                                text ->
                                        text.contains("String.translate")
                                                && text.contains(
                                                        "no overlay, screen or test script")),
                "The refusal should name the entry and the reason: " + reported);
        assertEquals("a", HostFunctions.String_translate_with("a", "x"));
    }

    @Test
    void anEmptyOrAbsentLanguageCodeUsesEnUs() throws Exception {
        shipped("codeless", "lang/en_us.json", "{\"a\": \"A\"}");
        language = "";
        assertEquals("A", translate("codeless", "a"), "An empty code should read en_us.");

        shipped("noCode", "lang/en_us.json", "{\"a\": \"A\"}");
        language = null;
        assertEquals("A", translate("noCode", "a"), "A null code should read en_us.");
    }

    @Test
    void translateWithFillsTheOnePlaceholderOnce() throws Exception {
        shipped(
                "filled",
                "lang/en_us.json",
                "{\"delta\": \"Delta %s\", \"two\": \"%s and %s\", \"plain\": \"Plain\"}");

        assertEquals("Delta X", translateWith("filled", "delta", "X"));
        assertEquals(
                "x and %s", translateWith("filled", "two", "x"), "Only the first %s is filled.");
        assertEquals(
                "Plain", translateWith("filled", "plain", "x"), "A text with no %s is unchanged.");
        assertEquals(
                "unknown", translateWith("filled", "unknown", "x"), "The key answers with no %s.");
        assertEquals(
                "Delta 50%",
                translateWith("filled", "delta", "50%"),
                "A percent in the value is not read as a format.");
    }

    @Test
    void theDispatchPutsBackTheModItFound() {
        assertNull(GrugScriptDispatch.runningMod());
        String outer = GrugScriptDispatch.begin("outer");
        assertNull(outer);
        assertEquals("outer", GrugScriptDispatch.runningMod());
        String inner = GrugScriptDispatch.begin("inner");
        assertEquals("outer", inner, "begin answers the mod that was running before it.");
        assertEquals("inner", GrugScriptDispatch.runningMod());
        GrugScriptDispatch.end(inner);
        assertEquals("outer", GrugScriptDispatch.runningMod(), "end puts the outer mod back.");
        GrugScriptDispatch.end(outer);
        assertNull(GrugScriptDispatch.runningMod());
    }
}
