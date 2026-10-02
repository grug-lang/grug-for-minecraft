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
    void rejectsAFileOutsideAScriptDirectory() {
        FileInfo bad = file("mymod/wrong/foo-Block.grug", "foo-Block", "Block", 1L);
        IllegalStateException e =
                assertThrows(
                        IllegalStateException.class,
                        () -> GrugFileIndex.classify(new FileInfo[] {bad}));
        assertEquals(true, e.getMessage().contains("must be placed inside a 'code/' or 'tests/'"));
    }

    @Test
    void acceptsAFileUnderTests() {
        List<GrugFileIndex.Entry> entries =
                GrugFileIndex.classify(
                        new FileInfo[] {file("mymod/tests/foo-Test.grug", "foo-Test", "Test", 3L)});
        assertEquals(1, entries.size());
        assertEquals(3L, entries.get(0).file().fileId());
    }

    @Test
    void acceptsATestNestedUnderTests() {
        List<GrugFileIndex.Entry> entries =
                GrugFileIndex.classify(
                        new FileInfo[] {
                            file("mymod/tests/foo/bar/baz-Test.grug", "baz-Test", "Test", 3L)
                        });
        assertEquals(1, entries.size());
    }

    @Test
    void holdsScriptsOnlyForCodeAndTests() {
        assertEquals(true, GrugFileIndex.holdsScripts("code"));
        assertEquals(true, GrugFileIndex.holdsScripts("tests"));
        assertEquals(false, GrugFileIndex.holdsScripts("assets"));
        assertEquals(false, GrugFileIndex.holdsScripts("screenshots"));
    }

    @Test
    void findsNoMisplacedTestInAWellLaidOutMod() {
        assertEquals(
                List.of(),
                GrugFileIndex.misplacedTests(
                        new FileInfo[] {
                            file("mymod/code/foo-Block.grug", "foo-Block", "Block", 1L),
                            file("mymod/tests/bar-Test.grug", "bar-Test", "Test", 2L),
                            file("mymod/tests/nested/baz-Test.grug", "baz-Test", "Test", 3L)
                        }));
    }

    @Test
    void findsATestLeftInCode() {
        assertEquals(
                List.of("mymod/code/bar-Test.grug"),
                GrugFileIndex.misplacedTests(
                        new FileInfo[] {
                            file("mymod/code/foo-Block.grug", "foo-Block", "Block", 1L),
                            file("mymod/code/bar-Test.grug", "bar-Test", "Test", 2L)
                        }));
    }

    @Test
    void findsATestWithNoModDirectory() {
        assertEquals(
                List.of("tests/bar-Test.grug"),
                GrugFileIndex.misplacedTests(
                        new FileInfo[] {file("tests/bar-Test.grug", "bar-Test", "Test", 2L)}));
    }

    @Test
    void acceptsBackslashSeparatorsWhenPlacingATest() {
        assertEquals(
                List.of(),
                GrugFileIndex.misplacedTests(
                        new FileInfo[] {
                            file("mymod\\tests\\bar-Test.grug", "bar-Test", "Test", 2L)
                        }));
    }

    @Test
    void classifyingReportsAMisplacedTest() {
        GrugFileIndex.classify(
                new FileInfo[] {file("mymod/code/bar-Test.grug", "bar-Test", "Test", 2L)});
        // Reported through the same queue the loaders drain into chat, and as a [GRUG CI] FAIL
        // line,
        // which is what run-loader.sh fails the run on. The adapter is null under test, which the
        // reporting path tolerates.
        synchronized (Grug.runtimeErrorQueue) {
            assertEquals(1, Grug.runtimeErrorQueue.size());
            assertEquals(
                    true,
                    Grug.runtimeErrorQueue
                            .peek()
                            .contains("'mymod/code/bar-Test.grug' must be placed inside"));
            Grug.runtimeErrorQueue.clear();
        }
    }

    @Test
    void classifyingAWellLaidOutModReportsNothing() {
        GrugFileIndex.classify(
                new FileInfo[] {
                    file("mymod/code/foo-Block.grug", "foo-Block", "Block", 1L),
                    file("mymod/tests/bar-Test.grug", "bar-Test", "Test", 2L)
                });
        synchronized (Grug.runtimeErrorQueue) {
            assertEquals(0, Grug.runtimeErrorQueue.size());
        }
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
