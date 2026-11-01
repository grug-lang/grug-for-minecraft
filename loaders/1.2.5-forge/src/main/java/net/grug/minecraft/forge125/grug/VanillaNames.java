package net.grug.minecraft.forge125.grug;

import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.grug.GrugVanillaIds;

/**
 * Resolves a name to a 1.2.5 block or item id.
 *
 * <p>The runtime is obfuscated, so the other loaders' trick of reflecting over named fields does
 * not work here: {@code Block.chest} is a one-letter field at run time. Instead the build dumps the
 * dev classes' names and ids into {@code /vanilla_names.txt}, and {@link GrugVanillaIds} reads them
 * back. See that class for the table and the key a name is matched by.
 *
 * <p>This is 1.2.5's own naming and nothing more. Turning a canonical name a mod wrote into the
 * name this game knows a block by, and this game's name back into the canonical one, is {@code
 * GrugVanillaBlocks}' job, so that the same table covers all five loaders. See #58.
 */
public final class VanillaNames {

    private static final String TABLE = "/vanilla_names.txt";

    private static final GrugVanillaIds IDS = readFromJar();

    /**
     * Reads the table out of the loader jar.
     *
     * <p>Kept apart from the parse in {@link GrugVanillaIds}, which core's unit tests drive,
     * because the table is a resource inside this jar: a missing or unreadable one is not something
     * a test can produce.
     */
    @GrugGenerated(
            "table resource: the table ships in the loader jar, so a missing one cannot be forced")
    private static GrugVanillaIds readFromJar() {
        return GrugVanillaIds.read(TABLE, VanillaNames.class.getResourceAsStream(TABLE));
    }

    /** The block id for the name, or -1. */
    public static int blockId(String path) {
        return IDS.blockId(path);
    }

    /**
     * This game's name for a vanilla block id, or null. It is the {@code key} of the block's MCP
     * field name, which is what {@link #blockId} is asked with, so the two directions match.
     * Mapping it to a canonical name a mod can compare against is {@code GrugVanillaBlocks}' job.
     */
    public static String blockName(int id) {
        return IDS.blockName(id);
    }

    /** The item id (its shiftedIndex) for the name, or -1. */
    public static int itemId(String path) {
        return IDS.itemId(path);
    }

    @GrugGenerated("utility class: never instantiated")
    private VanillaNames() {}
}
