package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.util.List;

/** Covers the validation and classification of compiled grug files. */
class GrugFileIndexTest {

    private static FileInfo file(String path, String entityName, String entityType, long fileId) {
        return new FileInfo(path, "", "", entityType, entityName, fileId, "");
    }

    @Test
    void classifiesAValidFileAndStripsTheSuffix() {
        List<GrugFileIndex.Entry> entries =
                GrugFileIndex.classify(
                        new FileInfo[] {
                            file("mymod/code/foo-Block.grug", "foo-Block", "Block", 7)
                        });
        assertEquals(1, entries.size());
        assertEquals("foo", entries.get(0).cleanName());
        assertEquals("Block", entries.get(0).entityType());
        assertEquals(7, entries.get(0).file().fileId());
    }

    @Test
    void keepsANameWithoutASuffix() {
        List<GrugFileIndex.Entry> entries =
                GrugFileIndex.classify(
                        new FileInfo[] {file("mymod/code/foo-Block.grug", "foo", "Block", 7)});
        assertEquals("foo", entries.get(0).cleanName());
    }

    @Test
    void rejectsAFileThatFailedToCompile() {
        FileInfo bad =
                new FileInfo(
                        "mymod/code/foo-Block.grug", "", "", "Block", "foo-Block", -1L, "boom");
        IllegalStateException e =
                assertThrows(
                        IllegalStateException.class,
                        () -> GrugFileIndex.classify(new FileInfo[] {bad}));
        assertEquals(true, e.getMessage().contains("Failed to compile"));
    }

    @Test
    void rejectsAFileOutsideCode() {
        FileInfo bad = file("mymod/wrong/foo-Block.grug", "foo-Block", "Block", 1L);
        IllegalStateException e =
                assertThrows(
                        IllegalStateException.class,
                        () -> GrugFileIndex.classify(new FileInfo[] {bad}));
        assertEquals(true, e.getMessage().contains("must be placed inside a 'code/' directory"));
    }

    @Test
    void rejectsAPathWithNoDirectory() {
        FileInfo bad = file("foo.grug", "foo", "Block", 1L);
        assertThrows(
                IllegalStateException.class, () -> GrugFileIndex.classify(new FileInfo[] {bad}));
    }

    @Test
    void acceptsBackslashSeparators() {
        List<GrugFileIndex.Entry> entries =
                GrugFileIndex.classify(
                        new FileInfo[] {
                            file("mymod\\code\\foo-Block.grug", "foo-Block", "Block", 1L)
                        });
        assertEquals(1, entries.size());
    }
}
