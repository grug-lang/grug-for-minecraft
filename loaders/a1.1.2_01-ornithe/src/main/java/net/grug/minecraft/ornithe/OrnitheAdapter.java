package net.grug.minecraft.ornithe;

import net.fabricmc.loader.api.FabricLoader;
import net.grug.minecraft.core.ModLoaderAdapter;
import net.grug.minecraft.grug.BlockPos;

import java.io.File;

public class OrnitheAdapter implements ModLoaderAdapter {

    @Override
    public File getGameDirectory() {
        return FabricLoader.getInstance().getGameDir().toFile();
    }

    @Override
    public boolean isDevelopmentEnvironment() {
        return FabricLoader.getInstance().isDevelopmentEnvironment();
    }

    @Override
    public void logInfo(String message) {
        GrugModLoader.LOGGER.info(message);
    }

    @Override
    public void logError(String message) {
        GrugModLoader.LOGGER.error(message);
    }

    // --- Registration Methods ---

    @Override
    public void registerBlock(String namespace, String name, long fileId) {
    }

    @Override
    public void registerItem(String namespace, String name, long fileId) {
    }

    @Override
    public void registerBlockEntity(String namespace, String name) {
    }

    @Override
    public void reloadRecipe(String resourcePath) {
    }

    // --- GUI & Inventory Methods ---

    @Override
    public void openGui(Object playerObj, Object blockEntityObj, Object guiBuilderObj) {
    }

    @Override
    public void consumeCraftingIngredients(Object blockEntityObj, double startSlot) {
    }

    @Override
    public double countItemInInventory(Object blockEntityObj, Object itemObj, double damage) {
        return 0;
    }

    @Override
    public void dropInventory(Object levelObj, double x, double y, double z) {
    }

    @Override
    public double extractItemFromInventory(Object blockEntityObj, Object itemObj, double damage, double amount) {
        return 0;
    }

    @Override
    public double getInventorySize(Object blockEntityObj) {
        return 0;
    }

    @Override
    public double getItemCountInSlot(Object blockEntityObj, double slot) {
        return 0;
    }

    @Override
    public double getItemDamageInSlot(Object blockEntityObj, double slot) {
        return 0;
    }

    @Override
    public Object getItemInSlot(Object blockEntityObj, double slot) {
        return null;
    }

    @Override
    public void setItemCountInSlot(Object blockEntityObj, double slot, double count) {
    }

    @Override
    public void setItemInSlot(Object blockEntityObj, double slot, Object itemObj, double count) {
    }

    @Override
    public void updateRecipeOutput(Object blockEntityObj, double startSlot, double outputSlot) {
    }

    // --- World & Entity Methods ---

    @Override
    public void setEntityDeltaMovement(Object entityObj, double dx, double dy, double dz) {
    }

    @Override
    public void spawnEntity(Object levelObj, Object entityObj) {
    }

    @Override
    public Object getBlockEntity(Object levelObj, double x, double y, double z) {
        return null;
    }

    @Override
    public Object getBlockEntityLevel(Object blockEntityObj) {
        return null;
    }

    @Override
    public BlockPos getBlockPosOfBlockEntity(Object blockEntityObj) {
        return null;
    }

    @Override
    public Object getItemFromRegistry(Object resourceLocationObj) {
        return null;
    }

    @Override
    public Object createItemEntity(Object levelObj, double x, double y, double z, Object itemStackObj) {
        return null;
    }

    @Override
    public Object createItemStack(Object itemObj) {
        return null;
    }

    @Override
    public Object createResourceLocation(String string) {
        return null;
    }
}
