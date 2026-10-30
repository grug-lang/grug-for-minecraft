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
     * Expires a host entity id, so a script that saved the id cannot resolve it again.
     *
     * <p>The render context is the first user: it is valid only for the call it was passed to, and
     * removing its id is what makes a saved context stale rather than a context from a later pass.
     */
    public static void removeEntity(long id) {
        GrugObject object = entityData.remove(id);
        if (object != null) {
            fnEntities.remove(object);
        }
    }

    /**
     * Registers the block entity a just-created grug entity stands for, under the entity's own id,
     * which is the id {@code me} holds.
     *
     * <p>Without this, {@code me} enters the entity table only when a host call resolves it while
     * {@link #currentlyInitializingBlockEntity} is bound, which happens only inside member scope
     * and {@code init}. A script that never touched {@code me} there then found no block entity
     * behind it in {@code tick} or any other export, so it needed a throwaway member-scope line
     * such as {@code level: Level = me.level()}. See #604.
     *
     * <p>The registration is rooted in {@link #fnEntities}, which is the block entity's own child
     * list for the duration of its creation, so it is freed with the block entity rather than kept
     * in a process-wide list forever. An id that already holds this same block entity is left
     * alone, which is the case when member scope resolved {@code me} and registered it then.
     */
    static void registerBlockEntity(long entityId, Object blockEntity) {
        if (entityId == Grug.INVALID_GRUG_ENTITY_ID || blockEntity == null) return;
        GrugObject registered = entityData.get(entityId);
        if (registered != null && registered.object == blockEntity) return;
        addEntityWithId(entityId, GrugEntityType.BlockEntity, blockEntity);
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
                long entityHandle = Grug.createEntity(fileId);
                registerBlockEntity(Grug.getEntityId(entityHandle), initializingBlockEntity);
                return entityHandle;
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

    /**
     * The context a block entity's {@code init()} was passed, which is what the context's methods
     * check before they act. It holds the block entity being initialized.
     */
    public static final class BlockEntityInitContext {
        final Object blockEntity;

        private BlockEntityInitContext(Object blockEntity) {
            this.blockEntity = blockEntity;
        }
    }

    private static BlockEntityInitContext blockEntityInitContext = null;

    /**
     * Opens the context of one block entity {@code init()} call, remembering the one it nests in.
     */
    static BlockEntityInitContext openBlockEntityInitContext(Object blockEntity) {
        blockEntityInitContext = new BlockEntityInitContext(blockEntity);
        return blockEntityInitContext;
    }

    /** Closes it again, so a saved context no longer matches, and reopens the enclosing one. */
    static void closeBlockEntityInitContext(BlockEntityInitContext enclosing) {
        blockEntityInitContext = enclosing;
    }

    /** The context of the block entity {@code init()} call open right now, or null otherwise. */
    public static BlockEntityInitContext blockEntityInitContext() {
        return blockEntityInitContext;
    }

    /**
     * Calls a block entity's {@code init()} with a fresh context, with the host entities the call
     * creates rooted in {@code owner} and {@code me} resolving to {@code blockEntity}. The swap has
     * the same hazard and the same fix as {@link #createEntityWithOwner}. {@code me} is bound for
     * the call because init is where a script first reaches for it: a block entity whose member
     * scope only declares has nothing bound yet.
     *
     * <p>The context is a grug entity of its own for exactly the duration of the call, so a script
     * that saves it in a member variable holds an entity that no longer exists, and the context's
     * methods refuse it. It is removed in the finally so a fatal raised inside the script cannot
     * leave it behind.
     */
    @GrugGenerated(
            "the call into the VM and its context entity: exercising it needs a live grug entity")
    public static void callBlockEntityInit(
            long entityHandle, long exportFnId, List<GrugObject> owner, Object blockEntity) {
        GrugStateLock.lock();
        try {
            List<GrugObject> oldFnEntities = fnEntities;
            Object oldInitializingBlockEntity = currentlyInitializingBlockEntity;
            BlockEntityInitContext enclosing = blockEntityInitContext;
            fnEntities = owner;
            currentlyInitializingBlockEntity = blockEntity;
            long contextHandle =
                    addEntity(
                            GrugEntityType.BlockEntityInitContext,
                            openBlockEntityInitContext(blockEntity));
            try {
                Grug.callExportFn(entityHandle, exportFnId, contextHandle);
            } finally {
                removeEntity(contextHandle);
                closeBlockEntityInitContext(enclosing);
                currentlyInitializingBlockEntity = oldInitializingBlockEntity;
                fnEntities = oldFnEntities;
            }
        } finally {
            GrugStateLock.unlock();
        }
    }

    /**
     * Runs {@code task} with a fresh root list, then restores the previous one.
     *
     * <p>An export that runs every frame creates host entities through calls such as {@code item}
     * and {@code vec3}. Rooting them in the list the call found would keep the previous frame's
     * entities alive forever, so an export that owns its entities for the whole call creates the
     * context, runs the export and cleans the context up inside here. The caller holds {@link
     * GrugStateLock}: the swap is process-global state and has to be in the same critical section
     * as the call, for the reason {@link #createEntityWithOwner} gives.
     */
    static void withFreshOwner(Runnable task) {
        List<GrugObject> oldFnEntities = fnEntities;
        fnEntities = new ArrayList<>();
        try {
            task.run();
        } finally {
            fnEntities = oldFnEntities;
        }
    }

    /**
     * Runs {@code task} under the state lock, for a caller that has to take the state lock before a
     * monitor of its own.
     *
     * <p>{@link GrugStateLock} is package-private because every entry into the native state lives
     * here. A loader's block entity still has to serialize its first initialization against both
     * the game's threads and a script export that already holds the state lock, and taking the
     * state lock first there keeps its order the same as every host call's.
     */
    public static void runLocked(Runnable task) {
        GrugStateLock.lock();
        try {
            task.run();
        } finally {
            GrugStateLock.unlock();
        }
    }
}
