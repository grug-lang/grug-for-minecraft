package net.grug.minecraft.grug;

import java.util.HashMap;
import java.util.Map;

/**
 * A mod's declared block and item scripts, in the two directions a lookup needs them: by the id the
 * game object gets and by the file id its script was compiled to.
 *
 * <p>{@code currentlyInitializingBlock} carries what an init export writes: a loader, or the
 * hot-reload re-init, points it at the block whose init is running, and the set_block_entity,
 * set_custom_render and similar host functions write their values into it. When the init has run,
 * the loader stores the finished data in the maps.
 */
public final class GrugDeclarations {
    private GrugDeclarations() {}

    public static final Map<String, GrugBlockData> declaredBlocks = new HashMap<>();
    public static final Map<Long, GrugBlockData> blockDataByFileId = new HashMap<>();

    public static final Map<String, GrugItemData> declaredItems = new HashMap<>();
    public static final Map<Long, GrugItemData> itemDataByFileId = new HashMap<>();

    public static GrugBlockData currentlyInitializingBlock = null;
}
