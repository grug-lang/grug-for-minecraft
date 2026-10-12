package net.grug.minecraft.grug;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The host entities a game object stands behind: the weak table every entity id resolves through,
 * the strong root lists a creation or tick roots its entities in, and the owner swapping a
 * block-entity scope does for its own creations.
 *
 * <p>grug's entity ids are its own, built here per entity type, and the native entity handle they
 * address is created and destroyed through the lock-taking entries in {@link Grug}.
 */
public final class GrugEntities {
    private GrugEntities() {}

    public static final WeakGrugValueMap entityData = new WeakGrugValueMap();
    private static final Map<GrugEntityType, Integer> nextEntityIndices = new HashMap<>();

    public static Object currentlyInitializingBlockEntity = null;

    /**
     * The default strong root for host entities. A block-entity member scope points {@link
     * #fnEntities} at its own owner list for the duration of a creation or tick, but everything
     * created outside that window (including exports like {@code output_taken} and {@code
     * item_inserted}, which do not swap the list) is retained here. It is not only member scope, so
     * it is not named for members; once the rooting becomes explicit this may become {@code
     * memberFnEntities}. See #89 and #93.
     */
    public static final List<GrugObject> retainedFnEntities = new ArrayList<>();

    public static List<GrugObject> fnEntities = retainedFnEntities;

    public static final Map<String, Long> entityFileIdsByName = new HashMap<>();

    static {
        for (GrugEntityType type : GrugEntityType.values()) {
            nextEntityIndices.put(type, 0);
        }
    }

    public static long addEntity(GrugEntityType type, Object object) {
        GrugObject grugObject = new GrugObject(type, object);
        int index = nextEntityIndices.get(type);
        nextEntityIndices.put(type, index + 1);
        long id = ((long) type.ordinal() << 32) | (index & 0xFFFFFFFFL);

        entityData.put(id, grugObject);
        fnEntities.add(grugObject);
        return id;
    }

    public static void addEntityWithId(long id, GrugEntityType type, Object object) {
        GrugObject grugObject = new GrugObject(type, object);
        entityData.put(id, grugObject);
        fnEntities.add(grugObject);
    }

    /**
     * {@link Grug#createEntity} with the host entities the call creates rooted in {@code owner} and
     * {@code me} resolving to {@code initializingBlockEntity}, under the state lock.
     *
     * <p>The root list and the {@code me} binding are process-global state that host functions read
     * while a call runs, so they have to be swapped inside the same critical section as the call. A
     * swap made around a call that takes the lock is a race with every other entry into grug: a
     * block-entity tick on the game thread swaps in a list it drops on return, and a chunk-compile
     * worker inside this call then roots the entities its member scope creates in that dropped
     * list. The script keeps their ids, the weak entity table drops them once the collector runs,
     * and the next host function that dereferences one throws inside the JNI call, which takes the
     * JVM down. Holding the lock across the swap, the call and the restore closes that window; the
     * lock is reentrant, so the native entry's own acquisition nests. See #258.
     */
    public static long createEntityWithOwner(
            long fileId, List<GrugObject> owner, Object initializingBlockEntity) {
        GrugStateLock.lock();
        try {
            List<GrugObject> oldFnEntities = fnEntities;
            Object oldInitializingBlockEntity = currentlyInitializingBlockEntity;
            fnEntities = owner;
            currentlyInitializingBlockEntity = initializingBlockEntity;
            try {
                return Grug.createEntity(fileId);
            } finally {
                currentlyInitializingBlockEntity = oldInitializingBlockEntity;
                fnEntities = oldFnEntities;
            }
        } finally {
            GrugStateLock.unlock();
        }
    }

    /**
     * {@link Grug#callExportFn} with the host entities the call creates rooted in {@code owner} and
     * {@code me} resolving to {@code initializingBlockEntity}. The swap has the same hazard and the
     * same fix as {@link #createEntityWithOwner}.
     */
    public static void callExportFnWithOwner(
            long entityHandle,
            long exportFnId,
            List<GrugObject> owner,
            Object initializingBlockEntity) {
        GrugStateLock.lock();
        try {
            List<GrugObject> oldFnEntities = fnEntities;
            Object oldInitializingBlockEntity = currentlyInitializingBlockEntity;
            fnEntities = owner;
            currentlyInitializingBlockEntity = initializingBlockEntity;
            try {
                Grug.callExportFn(entityHandle, exportFnId);
            } finally {
                currentlyInitializingBlockEntity = oldInitializingBlockEntity;
                fnEntities = oldFnEntities;
            }
        } finally {
            GrugStateLock.unlock();
        }
    }
}
