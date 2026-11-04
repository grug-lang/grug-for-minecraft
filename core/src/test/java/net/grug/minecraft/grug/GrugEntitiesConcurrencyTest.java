package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Covers the root lists against the two threads that reach them at once: the server thread roots
 * and unroots the entities a call creates while the client's render thread unroots a context id,
 * and both can land on the same list because {@link GrugEntities#fnEntities} is process-global. See
 * #1108.
 */
class GrugEntitiesConcurrencyTest {

    private static final long ENTITY_ID = 0x0000_0002_0000_0001L;

    @AfterEach
    void clearState() {
        GrugEntities.entityData.clear();
        GrugEntities.retainedFnEntities.clear();
        GrugEntities.fnEntities = GrugEntities.retainedFnEntities;
    }

    @Test
    void unrootsWhileAnotherThreadRootsTheSameList() throws Exception {
        // A long list widens the window in which a root lands in the middle of an unroot's
        // iteration, which is what the render thread hit.
        List<GrugObject> roots = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            roots.add(new GrugObject(GrugEntityType.Level, new Object()));
        }
        GrugEntities.fnEntities = roots;
        GrugObject absent = new GrugObject(GrugEntityType.Level, new Object());

        AtomicBoolean running = new AtomicBoolean(true);
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch start = new CountDownLatch(1);

        Thread rooter =
                new Thread(
                        () -> {
                            try {
                                start.await();
                                while (running.get()) {
                                    GrugEntities.addEntityWithId(
                                            ENTITY_ID, GrugEntityType.Item, new Object());
                                    GrugEntities.removeEntity(ENTITY_ID);
                                }
                            } catch (Throwable failure) {
                                failures.add(failure);
                            }
                        },
                        "rooter");
        // removeEntity is not used here: it also removes from the weak table, and this test is
        // about the root list alone, which is the iteration that threw.
        Thread unrooter =
                new Thread(
                        () -> {
                            try {
                                start.await();
                                while (running.get()) {
                                    GrugEntities.unroot(roots, absent);
                                }
                            } catch (Throwable failure) {
                                failures.add(failure);
                            }
                        },
                        "unrooter");

        rooter.start();
        unrooter.start();
        start.countDown();
        Thread.sleep(500);
        running.set(false);
        rooter.join();
        unrooter.join();

        assertTrue(
                failures.isEmpty(),
                () -> "rooting and unrooting the same list concurrently threw " + failures.get(0));
    }
}
