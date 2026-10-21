package net.grug.minecraft.ornithe.block;

import net.grug.minecraft.core.GrugCore;
import net.grug.minecraft.grug.Grug;
import net.grug.minecraft.grug.GrugBlockData;
import net.grug.minecraft.grug.GrugDeclarations;
import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.grug.GrugIdPool;
import net.grug.minecraft.grug.GrugItemData;
import net.grug.minecraft.ornithe.GrugModLoader;
import net.grug.minecraft.ornithe.block.entity.GrugBlockEntity;
import net.grug.minecraft.ornithe.item.GrugItem;
import net.grug.minecraft.ornithe.mixin.BlockEntityAccess;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.item.Item;
import net.ornithemc.osl.blocks.api.BlockRegistry;
import net.ornithemc.osl.core.api.util.NamespacedIdentifiers;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

public final class GrugBlocks {
    @GrugGenerated("utility class: never instantiated")
    private GrugBlocks() {}

    /**
     * First id searched for a block, just above the highest one vanilla Alpha 1.1.2_01 registers
     * (85). The room up to {@link #LAST_BLOCK_ID} is all a pack has: the old range started at 128
     * minus the block count, so it reached as low as 92 for the 36 blocks of the repository's mods.
     */
    public static final int START_BLOCK_ID = 86;

    /**
     * Alpha stores a block id in a signed byte, so an id of 128 or more reads back negative and
     * crashes the chunk when the block is placed. The search stops here for that reason.
     */
    public static final int LAST_BLOCK_ID = 127;

    /** First id searched for an item. */
    public static final int START_ITEM_ID = 400;

    public static final Map<String, Integer> BLOCK_SPRITES = new HashMap<>();
    public static final Map<String, Integer> ITEM_SPRITES = new HashMap<>();
    // Blocks with a resolved model: sprite id -> texture resource path
    public static final Map<Integer, String> BLOCK_SPRITE_PATHS = new HashMap<>();
    // Texture reference ("grug:block/foo") -> sprite id, so identical textures share a slot
    private static final Map<String, Integer> TEXTURE_SPRITES = new HashMap<>();
    private static int nextBlockSpriteId = 160;
    private static int nextItemSpriteId = 160;

    public static void init() {
        GrugModLoader.LOGGER.info(
                "Registering dynamic grug blocks and items in Ornithe (Alpha 1.1.2_01)...");

        BlockEntityAccess.grug$register(GrugBlockEntity.class, "grug:generic_block_entity");

        // Register Dynamic Blocks
        GrugIdPool blockIds =
                GrugIdPool.of(
                        "block", id -> Block.BY_ID[id] != null, START_BLOCK_ID, LAST_BLOCK_ID);
        blockIds.requireRoom(GrugModLoader.blockCountsByMod, modsDirectory());
        for (Map.Entry<String, Long> entry : GrugModLoader.blockFiles.entrySet()) {
            String cleanName = entry.getKey();
            long blockFileId = entry.getValue();

            String fullId = "grug:" + cleanName;
            GrugBlockData blockData = new GrugBlockData(fullId);
            GrugDeclarations.currentlyInitializingBlock = blockData;

            long tempEntityHandle = Grug.createEntity(blockFileId);
            long initFnId = Grug.getExportFnId("Block", "init");

            if (tempEntityHandle != 0 && initFnId != Grug.INVALID_GRUG_EXPORT_FN_ID) {
                Grug.callExportFn(tempEntityHandle, initFnId);
            }

            if (tempEntityHandle != 0) {
                Grug.destroyEntity(tempEntityHandle);
            }

            GrugDeclarations.declaredBlocks.put(fullId, blockData);
            GrugDeclarations.blockDataByFileId.put(blockFileId, blockData);
            GrugDeclarations.currentlyInitializingBlock = null;

            int blockId = blockIds.take();
            Material mat = stringToMaterial(blockData.material);
            GrugBlock block = new GrugBlock(blockId, blockFileId, mat, blockData.hardness);
            if (block.drawsCustomGeometry()) {
                // A block that draws its own geometry is not a full cube: it lets light through and
                // does not hide its neighbours' faces.
                block.setOpacity(0);
                Block.IS_SOLID[blockId] = false;
            }

            // Texture Registration
            String[] faceTextures = GrugBlockModels.resolveFaceTextures(cleanName);
            if (faceTextures != null) {
                int[] faceSprites = new int[faceTextures.length];
                for (int face = 0; face < faceTextures.length; face++) {
                    faceSprites[face] = allocateBlockSprite(faceTextures[face]);
                }
                block.faceSprites = faceSprites;
                block.sprite = faceSprites[2];
            } else {
                int blockSprite = nextBlockSpriteId++;
                BLOCK_SPRITES.put(cleanName, blockSprite);
                block.sprite = blockSprite;
            }

            BlockRegistry.register(blockId, NamespacedIdentifiers.from("grug", cleanName), block);
        }

        // Register Dynamic Items
        GrugIdPool itemIds =
                GrugIdPool.of(
                        "item", id -> Item.BY_ID[id] != null, START_ITEM_ID, Item.BY_ID.length - 1);
        itemIds.requireRoom(GrugModLoader.itemCountsByMod, modsDirectory());
        for (Map.Entry<String, Long> entry : GrugModLoader.itemFiles.entrySet()) {
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

            GrugDeclarations.declaredItems.put(fullId, itemData);
            GrugDeclarations.itemDataByFileId.put(itemFileId, itemData);

            int itemId = itemIds.take();
            GrugItem item = new GrugItem(itemId - 256, itemFileId);

            // Texture Registration
            int itemSprite = nextItemSpriteId++;
            ITEM_SPRITES.put(cleanName, itemSprite);
            item.setSprite(itemSprite);
        }
    }

    private static File modsDirectory() {
        return GrugCore.getAdapter().getGrugModsDirectory();
    }

    private static int allocateBlockSprite(String textureRef) {
        Integer existing = TEXTURE_SPRITES.get(textureRef);
        if (existing != null) {
            return existing;
        }
        int sprite = nextBlockSpriteId++;
        TEXTURE_SPRITES.put(textureRef, sprite);
        BLOCK_SPRITE_PATHS.put(sprite, GrugBlockModels.toTexturePath(textureRef));
        return sprite;
    }

    private static Material stringToMaterial(String materialName) {
        if (materialName == null) return Material.STONE;
        switch (materialName.toLowerCase()) {
            case "air":
                return Material.AIR;
            case "organic":
            case "dirt":
            case "grass":
                return Material.GRASS;
            case "wood":
                return Material.WOOD;
            case "stone":
                return Material.STONE;
            case "metal":
            case "iron":
                return Material.IRON;
            case "water":
                return Material.WATER;
            case "lava":
                return Material.LAVA;
            case "leaves":
                return Material.LEAVES;
            case "plant":
                return Material.PLANT;
            case "sponge":
                return Material.SPONGE;
            case "cloth":
            case "wool":
                return Material.WOOL;
            case "fire":
                return Material.FIRE;
            case "sand":
                return Material.SAND;
            case "decoration":
                return Material.DECORATION;
            case "glass":
                return Material.GLASS;
            case "tnt":
                return Material.TNT;
            case "coral":
            case "unused":
                return Material.CORAL;
            case "ice":
                return Material.ICE;
            case "snow":
            case "snow_layer":
            case "snow_block":
                return Material.SNOW;
            case "cactus":
                return Material.CACTUS;
            case "clay":
                return Material.CLAY;
            default:
                return Material.STONE;
        }
    }
}
