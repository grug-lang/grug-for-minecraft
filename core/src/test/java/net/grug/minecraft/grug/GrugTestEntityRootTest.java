package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Covers how long the host entities a test's own calls create are rooted: they last until the test
 * ends, because a test's member scope does too.
 *
 * <p>A per-call root list made them collectable between two ticks, and a test that captured a
 * screenshot before a resource reload and compared it after one then dereferenced an id that no
 * longer resolved, which took the game down inside the JNI call. See #883.
 */
class GrugTestEntityRootTest {

    @AfterEach
    void clearState() {
        GrugEntities.endTestEntities();
        GrugEntities.entityData.clear();
        GrugEntities.retainedFnEntities.clear();
        GrugEntities.fnEntities = GrugEntities.retainedFnEntities;
    }

    @Test
    void aTicksEntityStaysAliveUntilTheTestEnds() throws InterruptedException {
        GrugEntities.beginTestEntities();

        long id = aTickThatKeeps(new GrugScreenshot(0));

        assertFalse(
                collected(id), "the test's root list keeps the entity one of its ticks created");
        GrugEntities.endTestEntities();
        assertTrue(collected(id), "the test ending releases it");
    }

    @Test
    void anInitsEntityStaysAliveUntilTheTestEnds() throws InterruptedException {
        GrugEntities.beginTestEntities();

        long id = anInitThatKeeps(new GrugScreenshot(0));

        assertFalse(collected(id), "the test's root list keeps the entity its init created");
        GrugEntities.endTestEntities();
        assertTrue(collected(id), "the test ending releases it");
    }

    /**
     * One of a test's ticks, through the call the runner makes: its VM call is replaced by one that
     * creates a host entity and keeps its id, which is what a member assignment does.
     */
    private static long aTickThatKeeps(Object payload) {
        return keep(payload, GrugEntityType.TestTickContext);
    }

    /** The same for one of a test's {@code init()} calls. */
    private static long anInitThatKeeps(Object payload) {
        return keep(payload, GrugEntityType.TestInitContext);
    }

    private static long keep(Object payload, GrugEntityType contextType) {
        long[] id = new long[1];
        GrugTestState.callWithContext(
                contextType,
                () -> new Object(),
                () -> {},
                contextHandle -> {
                    id[0] = GrugEntities.addEntity(GrugEntityType.Screenshot, payload);
                    return true;
                });
        return id[0];
    }

    private static boolean collected(long id) throws InterruptedException {
        for (int attempt = 0; attempt < 50 && GrugEntities.entityData.get(id) != null; attempt++) {
            System.gc();
            Thread.sleep(10);
        }
        return GrugEntities.entityData.get(id) == null;
    }
}
