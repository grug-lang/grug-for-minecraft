package net.grug.minecraft.forge125.block;

import net.grug.minecraft.core.GrugCore;
import net.grug.minecraft.forge125.block.entity.GrugBlockEntity;
import net.grug.minecraft.forge125.item.GrugItem;
import net.grug.minecraft.forge125.mod_Grug;
import net.grug.minecraft.grug.Grug;
import net.grug.minecraft.grug.GrugBlockData;
import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.grug.GrugItemData;
import net.grug.minecraft.grug.GrugResourceIndex;
import net.minecraft.src.Block;
import net.minecraft.src.Item;
import net.minecraft.src.Material;
import net.minecraft.src.ModLoader;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * Registers every dynamically declared grug block and item with Forge's ModLoader.
 *
 * <p>Registration is deliberately lazy and idempotent. 1.2.5's entry point ({@code mod_Grug.load})
 * fills {@code mod_Grug.blockFiles} while the adapter already exists, so the adapter calls {@link
 * #init()} the first time a host function needs a registered block or item.
 */
public final class GrugBlocks {
    @GrugGenerated("utility class: never instantiated")
    private GrugBlocks() {}

    /**
     * First id searched when handing out ids. Vanilla 1.2.5 stops at 124 (redstone lamp), so this
     * is just above it. Ids are not taken blindly from here because a pack can have dozens of other
     * mods already holding them; the search below skips any that are taken.
     */
    public static final int START_BLOCK_ID = 125;

    /**
     * Block ids stop here. The top id is left free because some mods (Modular Force Field System in
     * Tekkit Classic, for one) hardcode it instead of searching for a free one.
     */
    public static final int LAST_BLOCK_ID = 254;

    /** First item id searched, for the same reason. */
    public static final int START_ITEM_ID = 400;

    /** The atlas grug block sprites live in. */
    private static final String TERRAIN_ATLAS = "/terrain.png";

    /** The atlas grug item sprites live in. */
    private static final String ITEMS_ATLAS = "/gui/items.png";

    /**
     * Texture path in the mods tree -> terrain sprite index.
     *
     * <p>Keyed by the file rather than by the block, because two blocks naming one texture can
     * share a slot (vanilla shares slots the same way) and FML only has 32 terrain slots for every
     * mod in the pack.
     */
    public static final Map<String, Integer> BLOCK_TEXTURES = new HashMap<String, Integer>();

    /** Texture path in the mods tree -> items sprite index, shared the same way. */
    public static final Map<String, Integer> ITEM_TEXTURES = new HashMap<String, Integer>();

    private static boolean registered = false;

    public static void init() {
        if (registered) return;

        // The maps are only filled once mod_Grug.load has classified the compiled files. Until
        // then there is nothing to register and this must stay re-runnable.
        if (mod_Grug.blockFiles.isEmpty() && mod_Grug.itemFiles.isEmpty()) return;

        registered = true;

        mod_Grug.LOGGER.info("Registering dynamic grug blocks and items in Forge 1.2.5...");
        ModLoader.registerTileEntity(GrugBlockEntity.class, "GrugBlockEntity");

        registerBlocks();
        registerItems();
    }

    private static void registerBlocks() {
        for (Map.Entry<String, Long> entry : mod_Grug.blockFiles.entrySet()) {
            String cleanName = entry.getKey();
            long blockFileId = entry.getValue();

            String fullId = "grug:" + cleanName;
            GrugBlockData blockData = new GrugBlockData(fullId);
            Grug.currentlyInitializingBlock = blockData;

            long tempEntityHandle = Grug.createEntity(blockFileId);
            long initFnId = Grug.getExportFnId("Block", "init");

            if (tempEntityHandle != 0 && initFnId != Grug.INVALID_GRUG_EXPORT_FN_ID) {
                Grug.callExportFn(tempEntityHandle, initFnId);
            }

            if (tempEntityHandle != 0) {
                Grug.destroyEntity(tempEntityHandle);
            }

            Grug.declaredBlocks.put(fullId, blockData);
            Grug.blockDataByFileId.put(blockFileId, blockData);
            Grug.currentlyInitializingBlock = null;

            int blockId = allocateBlockId();
            Material mat = stringToMaterial(blockData.material);
            GrugBlock block = new GrugBlock(blockId, blockFileId, mat, blockData.hardness);
            block.setBlockName("grug_" + cleanName);
            mod_Grug.LOGGER.info("grug:" + cleanName + " got block id " + blockId);

            assignBlockSprite(block, cleanName);

            ModLoader.registerBlock(block);
        }
    }

    private static void registerItems() {
        for (Map.Entry<String, Long> entry : mod_Grug.itemFiles.entrySet()) {
            String cleanName = entry.getKey();
            long itemFileId = entry.getValue();

            String fullId = "grug:" + cleanName;
            GrugItemData itemData = new GrugItemData(fullId);

            long tempEntityHandle = Grug.createEntity(itemFileId);
            long initFnId = Grug.getExportFnId("Item", "init");

            if (tempEntityHandle != 0 && initFnId != Grug.INVALID_GRUG_EXPORT_FN_ID) {
                Grug.callExportFn(tempEntityHandle, initFnId);
            }

            if (tempEntityHandle != 0) {
                Grug.destroyEntity(tempEntityHandle);
            }

            Grug.declaredItems.put(fullId, itemData);
            Grug.itemDataByFileId.put(itemFileId, itemData);

            int itemId = allocateItemId();
            // Item shifts its id by 256 internally, so this lands at Item.itemsList[itemId].
            GrugItem item = new GrugItem(itemId - 256, itemFileId);
            item.setItemName(cleanName);
            mod_Grug.LOGGER.info("grug:" + cleanName + " got item id " + itemId);

            assignItemIcon(item, cleanName);
        }
    }

    /**
     * Gives {@code block} the terrain slot of the texture its mod ships, if it ships one.
     *
     * <p>grug has no host function for naming a block's texture, so a block names one by
     * convention: the first of {@code assets/grug/textures/block/<name>.png} and its {@code _top},
     * {@code _side} and {@code _front} variants that some mod ships, which is the same search the
     * Ornithe loaders make for their flat fallback name. 1.2.5 draws every face of a block from one
     * sprite, so the first one found is the block's texture.
     *
     * <p>A block no mod ships a texture for is left alone rather than given a slot of its own: FML
     * has 32 terrain slots for the whole pack, so a slot spent on a block with nothing to draw is
     * one a mod that has a texture cannot have. It keeps the sprite {@code Block} gave it, which is
     * what a 1.2.5 block registered without a texture renders from anyway.
     */
    private static void assignBlockSprite(GrugBlock block, String cleanName) {
        String path = findTexture("block", cleanName);
        if (path == null) return;

        int sprite = claimSprite(BLOCK_TEXTURES, TERRAIN_ATLAS, path);
        block.sprite = sprite;
    }

    /**
     * Gives {@code item} the items-atlas slot of the texture its mod ships, if it ships one, on the
     * same terms as {@link #assignBlockSprite}.
     */
    private static void assignItemIcon(GrugItem item, String cleanName) {
        String path = findTexture("item", cleanName);
        if (path == null) return;

        int sprite = claimSprite(ITEM_TEXTURES, ITEMS_ATLAS, path);
        item.setIconIndex(sprite);
    }

    /**
     * The slot {@code path} renders in, claiming a free one the first time it is asked for.
     *
     * <p>Goes through {@code ModLoader.getUniqueSpriteIndex}, which is {@code
     * cpw.mods.fml.client.SpriteHelper} handing out the lowest slot no other mod has taken, from a
     * bitset of the slots FML reserves for mods in that atlas. Counting up from a number instead,
     * which is what this loader used to do, puts grug's sprites on top of vanilla's and of any
     * other mod's. FML answers -1 where there is no render engine, which is the dedicated server's
     * copy of ModLoader. This loader has no server run configuration, so that path is not exercised
     * here and the -1 is cached and assigned like any other value; a server run would be where to
     * find out what that does.
     */
    private static int claimSprite(Map<String, Integer> claimed, String atlas, String path) {
        Integer existing = claimed.get(path);
        if (existing != null) return existing.intValue();

        int sprite = ModLoader.getUniqueSpriteIndex(atlas);
        claimed.put(path, sprite);
        return sprite;
    }

    /**
     * The path in the mods tree of the texture a block or item of this name draws with, or null
     * when no mod ships one.
     *
     * <p>Null is an answer rather than a failure, and nothing is reported: with no host function
     * for naming a texture, a block that ships none is a block that asked for exactly that, which
     * is how the other loaders answer the same flat-name search when the block's model carries the
     * textures instead. The {@code _top}, {@code _side} and {@code _front} names are looked for
     * beside it because those are the ones a mod splits a block's faces into.
     */
    private static String findTexture(String type, String cleanName) {
        File modsDir = GrugCore.getAdapter().getGrugModsDirectory();
        for (String suffix : new String[] {"", "_top", "_side", "_front"}) {
            String path = "assets/grug/textures/" + type + "/" + cleanName + suffix + ".png";
            if (GrugResourceIndex.findDirectResource(modsDir, path) != null) return path;
        }
        return null;
    }

    /**
     * The first free block id at or after {@link #START_BLOCK_ID}. A pack's other mods may already
     * hold ids from that range, so taking the next one blindly would collide.
     */
    private static int allocateBlockId() {
        for (int id = START_BLOCK_ID; id <= LAST_BLOCK_ID; id++) {
            if (Block.blocksList[id] == null) return id;
        }
        throw Grug.fatal("No free block id for a grug block");
    }

    /** The first free item slot at or after {@link #START_ITEM_ID}. */
    private static int allocateItemId() {
        for (int id = START_ITEM_ID; id < Item.itemsList.length; id++) {
            if (Item.itemsList[id] == null) return id;
        }
        throw Grug.fatal("No free item id for a grug item");
    }

    private static Material stringToMaterial(String materialName) {
        if (materialName == null) return Material.rock;

        String name = materialName.toLowerCase();
        if (name.equals("air")) return Material.air;
        if (name.equals("organic") || name.equals("dirt") || name.equals("grass")) {
            return Material.grass;
        }
        if (name.equals("wood")) return Material.wood;
        if (name.equals("stone")) return Material.rock;
        if (name.equals("metal") || name.equals("iron")) return Material.iron;
        if (name.equals("water")) return Material.water;
        if (name.equals("lava")) return Material.lava;
        if (name.equals("leaves")) return Material.leaves;
        if (name.equals("plant")) return Material.plants;
        if (name.equals("sponge")) return Material.sponge;
        if (name.equals("cloth") || name.equals("wool")) return Material.cloth;
        if (name.equals("fire")) return Material.fire;
        if (name.equals("sand")) return Material.sand;
        if (name.equals("decoration")) return Material.circuits;
        if (name.equals("glass")) return Material.glass;
        if (name.equals("tnt")) return Material.tnt;
        if (name.equals("coral") || name.equals("unused")) return Material.unused;
        if (name.equals("ice")) return Material.ice;
        if (name.equals("snow") || name.equals("snow_layer") || name.equals("snow_block")) {
            return Material.snow;
        }
        if (name.equals("cactus")) return Material.cactus;
        if (name.equals("clay")) return Material.clay;
        return Material.rock;
    }
}
