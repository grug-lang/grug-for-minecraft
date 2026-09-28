package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/** Covers the filesystem half of the generated resource pack. */
class GrugResourceIndexTest {

    @TempDir Path mods;

    private void write(String relativePath, String content) throws IOException {
        Path path = mods.resolve(relativePath);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    @Test
    void findResourceReturnsTheFirstMatchingFile() throws IOException {
        write("mymod/assets/grug/models/block/foo.json", "{}");
        File found =
                GrugResourceIndex.findResource(
                        mods.toFile(), "assets", "grug", "models/block/foo.json");
        assertEquals(mods.resolve("mymod/assets/grug/models/block/foo.json").toFile(), found);
    }

    @Test
    void findResourceReturnsNullWhenMissing() {
        assertNull(GrugResourceIndex.findResource(mods.toFile(), "assets", "grug", "nope.json"));
    }

    @Test
    void findResourceReturnsNullForANonDirectory() {
        assertNull(
                GrugResourceIndex.findResource(
                        new File(mods.toFile(), "missing"), "assets", "grug", "x"));
    }

    @Test
    void findResourceScansPastAModWithoutTheFile() throws IOException {
        Files.createDirectories(mods.resolve("aamod"));
        write("zzmod/assets/grug/models/block/foo.json", "{}");
        File found =
                GrugResourceIndex.findResource(
                        mods.toFile(), "assets", "grug", "models/block/foo.json");
        assertEquals(mods.resolve("zzmod/assets/grug/models/block/foo.json").toFile(), found);
    }

    @Test
    void findDirectResourceLooksRelativeToTheModDirectory() throws IOException {
        write("mymod/assets/grug/lang/en_us.json", "{}");
        File found =
                GrugResourceIndex.findDirectResource(mods.toFile(), "assets/grug/lang/en_us.json");
        assertEquals(mods.resolve("mymod/assets/grug/lang/en_us.json").toFile(), found);
    }

    @Test
    void findDirectResourceReturnsNullWhenMissing() {
        assertNull(GrugResourceIndex.findDirectResource(mods.toFile(), "nope.txt"));
    }

    @Test
    void findDirectResourceScansPastAModWithoutTheFile() throws IOException {
        Files.createDirectories(mods.resolve("aamod"));
        write("zzmod/assets/grug/lang/en_us.json", "{}");
        File found =
                GrugResourceIndex.findDirectResource(mods.toFile(), "assets/grug/lang/en_us.json");
        assertEquals(mods.resolve("zzmod/assets/grug/lang/en_us.json").toFile(), found);
    }

    @Test
    void findDirectResourceReturnsNullForANonDirectory() {
        assertNull(GrugResourceIndex.findDirectResource(new File(mods.toFile(), "missing"), "x"));
    }

    @Test
    void findNamespacesIgnoresModsWithoutAssetsOrData() throws IOException {
        Files.createDirectories(mods.resolve("baremod"));
        assertTrue(GrugResourceIndex.findNamespaces(mods.toFile()).isEmpty());
    }

    @Test
    void findNamespacesCollectsFromBothAssetsAndData() throws IOException {
        write("assets_only/assets/grug/x.json", "{}");
        write("data_only/data/grug/x.json", "{}");
        Set<String> namespaces = GrugResourceIndex.findNamespaces(mods.toFile());
        assertTrue(namespaces.contains("grug"), namespaces.toString());
    }

    @Test
    void findResourcesSkipsAPathThatIsAFile() throws IOException {
        write("mymod/assets/grug/models", "not a directory");
        assertTrue(
                GrugResourceIndex.findResources(mods.toFile(), "assets", "grug", "models", null)
                        .isEmpty());
    }

    @Test
    void findResourcesScansEveryMod() throws IOException {
        write("aamod/assets/grug/models/a.json", "{}");
        write("zzmod/assets/grug/models/b.json", "{}");
        List<String> found =
                GrugResourceIndex.findResources(mods.toFile(), "assets", "grug", "models", null);
        assertTrue(found.contains("models/a.json"), found.toString());
        assertTrue(found.contains("models/b.json"), found.toString());
    }

    @Test
    void findResourcesReportsNoFailuresForAGoodWalk() throws IOException {
        write("mymod/assets/grug/models/a.json", "{}");
        List<String> failures = new java.util.ArrayList<>();
        GrugResourceIndex.findResources(mods.toFile(), "assets", "grug", "models", failures);
        assertTrue(failures.isEmpty(), failures.toString());
    }

    @Test
    void findResourcesReturnsEmptyForANonDirectory() {
        assertTrue(
                GrugResourceIndex.findResources(
                                new File(mods.toFile(), "missing"), "assets", "grug", "x", null)
                        .isEmpty());
    }

    @Test
    void findNamespacesCollectsAssetsAndData() throws IOException {
        write("mymod/assets/grug/lang/en_us.json", "{}");
        write("mymod/data/other/thing.json", "{}");
        Set<String> namespaces = GrugResourceIndex.findNamespaces(mods.toFile());
        assertTrue(namespaces.contains("grug"), namespaces.toString());
        assertTrue(namespaces.contains("other"), namespaces.toString());
    }

    @Test
    void findNamespacesIsEmptyWithoutModDirectories() {
        assertTrue(GrugResourceIndex.findNamespaces(mods.toFile()).isEmpty());
    }

    @Test
    void findResourcesListsRelativePaths() throws IOException {
        write("mymod/assets/grug/models/block/foo.json", "{}");
        write("mymod/assets/grug/models/block/sub/bar.json", "{}");
        List<String> found =
                GrugResourceIndex.findResources(
                        mods.toFile(), "assets", "grug", "models/block", null);
        assertTrue(found.contains("models/block/foo.json"), found.toString());
        assertTrue(found.contains("models/block/sub/bar.json"), found.toString());
    }

    @Test
    void findResourcesReturnsEmptyWhenMissing() {
        assertTrue(
                GrugResourceIndex.findResources(mods.toFile(), "assets", "grug", "nope", null)
                        .isEmpty());
    }

    @Test
    void mergeLangRewritesAndMergesEveryMod() throws IOException {
        write(
                "onemod/assets/grug/lang/en_us.json",
                "{\"block.grug.foo\": \"Foo\", \"item.grug.bar\": \"Bar\"}");
        write("twomod/assets/grug/lang/en_us.json", "{\"tile.grug.baz.name\": \"Baz\"}");
        String merged =
                new String(
                        GrugResourceIndex.mergeLang(mods.toFile(), "en_us"),
                        StandardCharsets.UTF_8);
        assertTrue(merged.contains("tile.grug.foo.name=Foo"), merged);
        assertTrue(merged.contains("item.grug.bar.name=Bar"), merged);
        assertTrue(merged.contains("tile.grug.baz.name=Baz"), merged);
    }

    @Test
    void mergeLangFallsBackToTheExactFilename() throws IOException {
        write("mymod/assets/grug/lang/en_US.json", "{\"key\": \"Value\"}");
        String merged =
                new String(
                        GrugResourceIndex.mergeLang(mods.toFile(), "en_US"),
                        StandardCharsets.UTF_8);
        assertTrue(merged.contains("key=Value"), merged);
    }

    @Test
    void mergeLangReturnsNullWhenNoModShipsTheLanguage() {
        assertNull(GrugResourceIndex.mergeLang(mods.toFile(), "en_us"));
    }

    @Test
    void mergeLangSkipsAMalformedFile() throws IOException {
        write("mymod/assets/grug/lang/en_us.json", "{ not json");
        byte[] merged = GrugResourceIndex.mergeLang(mods.toFile(), "en_us");
        assertFalse(merged == null);
        assertEquals("", new String(merged, StandardCharsets.UTF_8));
    }

    @Test
    void mergeLangIgnoresAModWithoutAssets() throws IOException {
        Files.createDirectories(mods.resolve("nomoddir"));
        assertNull(GrugResourceIndex.mergeLang(mods.toFile(), "en_us"));
    }

    @Test
    void mergeLangReturnsNullForANonDirectory() {
        assertNull(GrugResourceIndex.mergeLang(new File(mods.toFile(), "missing"), "en_us"));
    }

    @Test
    void mergeLangIgnoresAnAssetsDirectoryWithNoNamespaces() throws IOException {
        Files.createDirectories(mods.resolve("mymod/assets"));
        assertNull(GrugResourceIndex.mergeLang(mods.toFile(), "en_us"));
    }

    @Test
    void mergeLangSkipsAFileThatIsNotJson() throws IOException {
        write("mymod/assets/grug/lang/en_us.json", "not an object");
        byte[] merged = GrugResourceIndex.mergeLang(mods.toFile(), "en_us");
        assertEquals("", new String(merged, StandardCharsets.UTF_8));
    }

    @Test
    void mergeLangKeepsKeysThatAlreadyEndInName() throws IOException {
        write(
                "mymod/assets/grug/lang/en_us.json",
                "{\"block.grug.foo.name\": \"Foo\", \"item.grug.bar.name\": \"Bar\"}");
        String merged =
                new String(
                        GrugResourceIndex.mergeLang(mods.toFile(), "en_us"),
                        StandardCharsets.UTF_8);
        assertTrue(merged.contains("tile.grug.foo.name=Foo"), merged);
        assertTrue(merged.contains("item.grug.bar.name=Bar"), merged);
    }
}
