package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.grug.minecraft.core.GrugCore;
import net.grug.minecraft.core.GrugTestRunner;
import net.grug.minecraft.core.ModLoaderAdapter;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;

/**
 * Covers the runner's half of the test root list: it starts the list before the test's entity
 * exists and clears it when the test ends, so an entity a test's member keeps survives every one of
 * its ticks and no longer than that.
 *
 * <p>{@link GrugTestEntityRootTest} pins what a tick's call roots; this pins the two calls that
 * make that list last as long as the test, through {@link GrugTestRunner} rather than around it.
 * See #883.
 */
class GrugTestRunnerEntityRootTest {

    private Object savedAdapter;

    @BeforeEach
    void installAdapter() throws Exception {
        // The runner restores the player through the adapter when a test ends, so there has to be
        // one; nothing here cares what it does.
        Field field = GrugCore.class.getDeclaredField("adapter");
        field.setAccessible(true);
        savedAdapter = field.get(null);
        field.set(
                null,
                Proxy.newProxyInstance(
                        getClass().getClassLoader(),
                        new Class<?>[] {ModLoaderAdapter.class},
                        (proxy, method, args) -> null));
        GrugEntities.entityData.clear();
        GrugEntities.beginTestEntities();
        GrugTestState.testNotDone = false;
        GrugTestState.testExpectedError = null;
        GrugTestState.currentTestMod = null;
    }

    @AfterEach
    void restoreState() throws Exception {
        Field field = GrugCore.class.getDeclaredField("adapter");
        field.setAccessible(true);
        field.set(null, savedAdapter);
        GrugEntities.entityData.clear();
        GrugEntities.endTestEntities();
        GrugTestState.testNotDone = false;
        GrugTestState.testExpectedError = null;
        GrugTestState.currentTestMod = null;
    }

    /**
     * A test that keeps a host entity in a member, the shape of a screenshot captured before a
     * reload and compared after it.
     */
    private static class KeepsAnEntity implements GrugTestRunner.TestEntityOps {
        final long[] id = {0};
        final int ticksToRun = 3;
        int calls = 0;

        @Override
        public long createEntity(long fileId) {
            return 100;
        }

        @Override
        public long getExportFnId(String entityType, String fnName) {
            return fnName.equals("init") ? Grug.INVALID_GRUG_EXPORT_FN_ID : 1;
        }

        @Override
        public boolean callExportFn(long entityHandle, long exportFnId) {
            // The real path one of the test's ticks takes, so what it creates is rooted the way a
            // tick's entities are.
            GrugTestState.callWithContext(
                    GrugEntityType.TestTickContext,
                    () -> new Object(),
                    () -> {},
                    contextHandle -> {
                        if (id[0] == 0) {
                            id[0] =
                                    GrugEntities.addEntity(
                                            GrugEntityType.Screenshot, new GrugScreenshot(0));
                        }
                        GrugTestState.testNotDone = calls < ticksToRun;
                        return true;
                    });
            calls++;
            return true;
        }

        @Override
        public boolean callInitFn(long entityHandle, long exportFnId) {
            return true;
        }

        @Override
        public void destroyEntity(long entityHandle) {}
    }

    @Test
    void aTicksEntitySurvivesEveryTickAndGoesWhenTheTestEnds() throws InterruptedException {
        KeepsAnEntity ops = new KeepsAnEntity();
        GrugTestRunner runner =
                new GrugTestRunner(Map.of("mymod/tests/a-Test.grug", 1L), List.of(), ops);

        runner.tick(null);
        assertFalse(ops.id[0] == 0, "the test's first tick created the entity it keeps");
        long id = ops.id[0];

        // The ticks after the one that created it, which is where a list that was started only
        // after the first tick would have cleared it.
        for (int i = 0; i < ops.ticksToRun; i++) {
            assertFalse(collected(id), "the entity survives the test's tick " + (i + 1));
            runner.tick(null);
        }

        // The test stops asking for ticks, so this call ends it.
        runner.tick(null);
        assertTrue(runner.isFinished(), "the only test is over");
        assertTrue(collected(id), "the test ending releases the entity its member kept");
    }

    private static boolean collected(long id) throws InterruptedException {
        for (int attempt = 0; attempt < 50 && GrugEntities.entityData.get(id) != null; attempt++) {
            System.gc();
            Thread.sleep(10);
        }
        return GrugEntities.entityData.get(id) == null;
    }
}
