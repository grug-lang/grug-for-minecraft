package net.grug.minecraft.core;

import net.grug.minecraft.grug.BlockPos;
import net.grug.minecraft.grug.Vec3;

import java.io.File;

public interface ModLoaderAdapter {
    // --- TODO: Give this section a title, like the other sections below

    File getGameDirectory();

    /** The directory the loaded grug mods live in, which is what the screenshot tree is under. */
    File getGrugModsDirectory();

    void openGui(Object playerObj, Object blockEntityObj, Object guiBuilderObj);

    // --- Logging Abstraction --- TODO: Give this section a better title

    void logInfo(String message);

    void logError(String message);

    // --- Game Functions Abstraction --- TODO: Give this section a better title

    // TODO: Sort the below methods into entities, classes and host fns, and sort
    // each alphabetically

    void setEntityDeltaMovement(Object entityObj, double dx, double dy, double dz);

    void spawnEntity(Object levelObj, Object entityObj);

    void consumeCraftingIngredients(Object blockEntityObj, double startSlot);

    double countItemInInventory(Object blockEntityObj, Object itemObj, double damage);

    void dropInventory(Object levelObj, double x, double y, double z);

    double extractItemFromInventory(
            Object blockEntityObj, Object itemObj, double damage, double amount);

    Object getBlockEntity(Object levelObj, double x, double y, double z);

    Object getBlockEntityLevel(Object blockEntityObj);

    BlockPos getBlockPosOfBlockEntity(Object blockEntityObj);

    double getInventorySize(Object blockEntityObj);

    double insertItemIntoInventory(
            Object blockEntityObj, Object itemObj, double damage, double amount);

    double getItemCountInSlot(Object blockEntityObj, double slot);

    double getItemDamageInSlot(Object blockEntityObj, double slot);

    Object getItemInSlot(Object blockEntityObj, double slot);

    Object getItemFromRegistry(Object resourceLocationObj);

    Object createItemEntity(Object levelObj, double x, double y, double z, Object itemStackObj);

    Object createItemStack(Object itemObj, double damage);

    double getItemEntityDamage(Object itemEntityObj);

    Object findItemEntity(Object levelObj, double x, double y, double z, double radius);

    Object createResourceLocation(String string);

    void setItemCountInSlot(Object blockEntityObj, double slot, double count);

    void setItemDamageInSlot(Object blockEntityObj, double slot, double damage);

    void setItemInSlot(Object blockEntityObj, double slot, Object itemObj, double count);

    void updateRecipeOutput(Object blockEntityObj, double startSlot);

    void placeBlock(Object levelObj, double x, double y, double z, String blockName);

    boolean hasNeighborSignal(Object levelObj, double x, double y, double z);

    String getBlock(Object levelObj, double x, double y, double z);

    boolean isAir(Object levelObj, double x, double y, double z);

    double countItemEntities(Object levelObj, double x, double y, double z, double radius);

    void notifyNeighbors(Object levelObj, double x, double y, double z);

    double takeItemFromSlot(Object blockEntityObj, double slot, double amount);

    /**
     * Takes part of a block entity's crafting result. Unlike {@link #takeItemFromSlot} this is not
     * an inventory slot: a grug block entity keeps the result in its own holder, and a reference
     * tile computes it on demand.
     */
    double takeCraftingResult(Object blockEntityObj, double amount);

    Object getClientLevel();

    /** The local client player, or null when there is none. Lets a test drive GUI.open directly. */
    Object getPlayer();

    /**
     * Whether the client has received the chunks a test setup builds in: the neighbourhood around
     * the player has terrain below the player's feet. A write into a chunk the client has not
     * received lands in a placeholder chunk that the arriving real chunk then replaces, losing the
     * block and its block entity.
     */
    boolean isWorldReady(Object playerObj);

    /**
     * Whether this loader registers the crafting recipes mods ship under {@code
     * data/<namespace>/recipes}.
     *
     * <p>Every loader does, each through its own version's recipe machinery: the Ornithe loaders
     * parse the tree with {@code GrugRecipeTree} and hand the result to {@code CraftingManager},
     * StationAPI hands the files to its recipe registry, 1.20.6 serves the tree to Forge as a pack
     * so the level's own recipe manager loads it, and 1.2.5 parses the tree and calls the game's
     * own {@code ModLoader.addRecipe} and {@code addShapelessRecipe}. A test asserting a shipped
     * recipe asks this before asserting, so a future loader that cannot do it does not fail the run
     * for it.
     */
    boolean registersModRecipes();

    /**
     * Serialises a block entity's NBT and reads it straight back, so tests can cover the save/load
     * paths without the game actually saving and reloading a world.
     */
    void roundTripNbt(Object blockEntityObj);

    Vec3 getTestOrigin();

    Vec3 setupGraphicsTestCamera();

    void restoreCameraAfterGraphicsTest();

    void useBlockForTest(Object levelObj, double x, double y, double z);

    /**
     * Captures the rectangle and compares it against the reference directory. When {@code
     * tolerancePercent} is 0 the capture must match pixel-for-pixel; otherwise it passes when at
     * most that percentage of pixels differ.
     */
    void assertScreenshotEquals(
            String referencePath,
            double x1,
            double y1,
            double x2,
            double y2,
            double tolerancePercent);
}
