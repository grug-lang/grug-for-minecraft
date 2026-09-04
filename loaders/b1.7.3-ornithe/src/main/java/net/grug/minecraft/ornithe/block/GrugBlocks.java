package net.grug.minecraft.ornithe.block;

import net.grug.minecraft.grug.Grug;
import net.grug.minecraft.grug.GrugBlockData;
import net.grug.minecraft.grug.GrugItemData;
import net.grug.minecraft.ornithe.GrugModLoader;
import net.grug.minecraft.ornithe.block.entity.GrugBlockEntity;
import net.grug.minecraft.ornithe.item.GrugItem;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.material.Material;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.ornithemc.osl.blocks.api.BlockRegistry;
import net.ornithemc.osl.core.api.util.NamespacedIdentifiers;

import java.lang.reflect.Method;
import java.util.Map;

public final class GrugBlocks {
    private static int nextBlockId = 160;
    private static int nextItemId = 400;

    public static void init() {
        GrugModLoader.LOGGER.info("Registering dynamic grug blocks and items in Ornithe...");

        try {
            Method registerMethod = BlockEntity.class.getDeclaredMethod("register", Class.class, String.class);
            registerMethod.setAccessible(true);
            registerMethod.invoke(null, GrugBlockEntity.class, "grug:generic_block_entity");
        } catch (Exception e) {
            GrugModLoader.LOGGER.error("Failed to register GrugBlockEntity via reflection!", e);
        }

        // Register Dynamic Blocks
        for (Map.Entry<String, Long> entry : GrugModLoader.blockFiles.entrySet()) {
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

            int blockId = nextBlockId++;
            Material mat = stringToMaterial(blockData.material);
            GrugBlock block = new GrugBlock(blockId, blockFileId, mat, blockData.hardness);
            block.setKey("grug." + cleanName);

            BlockRegistry.register(
                    blockId,
                    NamespacedIdentifiers.from("grug", cleanName),
                    block);

            Item.BY_ID[blockId] = new BlockItem(blockId - 256).setKey("grug." + cleanName);
        }

        // Register Dynamic Items
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

            Grug.declaredItems.put(fullId, itemData);
            Grug.itemDataByFileId.put(itemFileId, itemData);

            int itemId = nextItemId++;
            GrugItem item = new GrugItem(itemId - 256, itemFileId);
            item.setKey("grug." + cleanName);
        }
    }

    private static Material stringToMaterial(String materialName) {
        if (materialName == null)
            return Material.STONE;
        return switch (materialName.toLowerCase()) {
            case "air" -> Material.AIR;
            case "organic", "dirt" -> Material.DIRT;
            case "wood" -> Material.WOOD;
            case "stone" -> Material.STONE;
            case "metal" -> Material.IRON;
            case "water" -> Material.WATER;
            case "lava" -> Material.LAVA;
            case "leaves" -> Material.LEAVES;
            case "plant" -> Material.PLANT;
            case "sponge" -> Material.SPONGE;
            case "cloth", "wool" -> Material.WOOL;
            case "fire" -> Material.FIRE;
            case "sand" -> Material.SAND;
            case "decoration" -> Material.DECORATION;
            case "glass" -> Material.GLASS;
            case "tnt" -> Material.TNT;
            case "coral", "unused" -> Material.CORAL;
            case "ice" -> Material.ICE;
            case "snow", "snow_block" -> Material.SNOW;
            case "cactus" -> Material.CACTUS;
            case "clay" -> Material.CLAY;
            case "pumpkin" -> Material.PUMPKIN;
            case "portal" -> Material.PORTAL;
            case "cake" -> Material.CAKE;
            case "web", "cobweb" -> Material.COBWEB;
            case "piston" -> Material.PISTON;
            default -> Material.STONE;
        };
    }
}
