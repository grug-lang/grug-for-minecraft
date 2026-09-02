package net.grug.minecraft.core;

import net.grug.minecraft.grug.BlockPos;
import java.io.File;

public interface ModLoaderAdapter {
    File getGameDirectory();

    boolean isDevelopmentEnvironment();

    void registerBlock(String namespace, String name, long fileId);

    void registerItem(String namespace, String name, long fileId);

    void registerBlockEntity(String namespace, String name);

    void openGui(Object playerObj, Object blockEntityObj, Object guiBuilderObj);

    void reloadRecipe(String resourcePath);

    // --- Logging Abstraction ---

    void logInfo(String message);

    void logError(String message);

    // --- Game Functions Abstraction ---

    void setEntityDeltaMovement(Object entityObj, double dx, double dy, double dz);

    void spawnEntity(Object levelObj, Object entityObj);

    void consumeCraftingIngredients(Object blockEntityObj, double startSlot);

    double countItemInInventory(Object blockEntityObj, Object itemObj, double damage);

    void dropInventory(Object levelObj, double x, double y, double z);

    double extractItemFromInventory(Object blockEntityObj, Object itemObj, double damage, double amount);

    Object getBlockEntity(Object levelObj, double x, double y, double z);

    Object getBlockEntityLevel(Object blockEntityObj);

    BlockPos getBlockPosOfBlockEntity(Object blockEntityObj);

    double getInventorySize(Object blockEntityObj);

    double getItemCountInSlot(Object blockEntityObj, double slot);

    double getItemDamageInSlot(Object blockEntityObj, double slot);

    Object getItemInSlot(Object blockEntityObj, double slot);

    Object getItemFromRegistry(Object resourceLocationObj);

    Object createItemEntity(Object levelObj, double x, double y, double z, Object itemStackObj);

    Object createItemStack(Object itemObj);

    Object createResourceLocation(String string);

    void setItemCountInSlot(Object blockEntityObj, double slot, double count);

    void setItemInSlot(Object blockEntityObj, double slot, Object itemObj, double count);

    void updateRecipeOutput(Object blockEntityObj, double startSlot, double outputSlot);
}
