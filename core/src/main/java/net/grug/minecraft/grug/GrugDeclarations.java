package net.grug.minecraft.grug;

import java.util.HashMap;
import java.util.Map;

/**
 * A mod's declared block and item scripts, in the two directions a lookup needs them: by the id the
 * game object gets and by the file id its script was compiled to.
 *
 * <p>{@link #initializeBlock} runs a block's init export with a {@code BlockInitContext}, whose
 * set_block_entity, set_custom_render and similar methods write their values into the block's data.
 * When the init has run, the loader stores the finished data in the maps.
 */
public final class GrugDeclarations {
    private GrugDeclarations() {}

    public static final Map<String, GrugBlockData> declaredBlocks = new HashMap<>();
    public static final Map<Long, GrugBlockData> blockDataByFileId = new HashMap<>();

    public static final Map<String, GrugItemData> declaredItems = new HashMap<>();
    public static final Map<Long, GrugItemData> itemDataByFileId = new HashMap<>();

    /**
     * The context the open {@code init()} call was passed, which is what the context's methods
     * check before they act. It holds the block whose init is running, and a new one is made for
     * every call and cleared when it returns, so a context saved in a member variable and used
     * later is refused.
     */
    public static final class InitContext {
        final GrugBlockData block;

        InitContext(GrugBlockData block) {
            this.block = block;
        }
    }

    private static InitContext initContext = null;

    /** Opens the context of one block {@code init()} call. */
    static InitContext openInitContext(GrugBlockData block) {
        initContext = new InitContext(block);
        return initContext;
    }

    /** Closes it again, so a saved context no longer matches. */
    static void closeInitContext() {
        initContext = null;
    }

    /** The context of the block {@code init()} call that is open right now, or null otherwise. */
    public static InitContext initContext() {
        return initContext;
    }

    /**
     * Creates a temporary entity of the block's script, calls its {@code init()} with a fresh
     * context that declares into {@code block}, and destroys the entity again.
     *
     * <p>The context is a grug entity of its own for exactly the duration of the call, so a script
     * that saves it in a member variable holds an entity that no longer exists, and the context's
     * methods refuse it. It is removed in the finally so a fatal raised inside the script cannot
     * leave it behind.
     */
    @GrugGenerated(
            "the call into the VM and its context entity: exercising it needs a live grug entity")
    public static void initializeBlock(GrugBlockData block, long blockFileId) {
        long tempEntityHandle = Grug.createEntity(blockFileId);
        long initFnId = Grug.getExportFnId("Block", "init");

        if (tempEntityHandle != 0 && initFnId != Grug.INVALID_GRUG_EXPORT_FN_ID) {
            long contextHandle =
                    GrugEntities.addEntity(GrugEntityType.BlockInitContext, openInitContext(block));
            try {
                Grug.callExportFn(tempEntityHandle, initFnId, contextHandle);
            } finally {
                GrugEntities.removeEntity(contextHandle);
                closeInitContext();
            }
        }

        if (tempEntityHandle != 0) {
            Grug.destroyEntity(tempEntityHandle);
        }
    }
}
