package net.grug.minecraft.forge125.block;

import net.grug.minecraft.forge125.block.entity.GrugBlockEntity;
import net.grug.minecraft.forge125.item.GrugItem;
import net.grug.minecraft.forge125.mod_Grug;
import net.grug.minecraft.grug.Grug;
import net.grug.minecraft.grug.GrugBlockData;
import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.grug.GrugItemData;
import net.minecraft.src.Block;
import net.minecraft.src.Item;
import net.minecraft.src.Material;
import net.minecraft.src.ModLoader;

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
     * is the start of the range Forge mods conventionally use. Ids are not taken blindly from here
     * because a pack can have dozens of other mods already holding them.
     */
    public static final int START_BLOCK_ID = 200;

    /** First item id searched, for the same reason. */
    public static final int START_ITEM_ID = 400;

    /** Block script clean name -> terrain sprite index. */
    public static final Map<String, Integer> BLOCK_SPRITES = new HashMap<String, Integer>();

    /** Item script clean name -> items sprite index. */
    public static final Map<String, Integer> ITEM_SPRITES = new HashMap<String, Integer>();

    private static int nextBlockSpriteId = 160;
    private static int nextItemSpriteId = 160;

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

            int blockSprite = nextBlockSpriteId++;
            BLOCK_SPRITES.put(cleanName, blockSprite);
            block.sprite = blockSprite;

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

            int itemSprite = nextItemSpriteId++;
            ITEM_SPRITES.put(cleanName, itemSprite);
            item.setIconIndex(itemSprite);
        }
    }

    /**
     * The first free block id at or after {@link #START_BLOCK_ID}. A pack's other mods may already
     * hold ids from that range, so taking the next one blindly would collide.
     */
    private static int allocateBlockId() {
        for (int id = START_BLOCK_ID; id < Block.blocksList.length; id++) {
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
