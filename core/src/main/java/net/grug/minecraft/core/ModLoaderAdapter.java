package net.grug.minecraft.core;

import net.grug.minecraft.grug.BlockPos;
import net.grug.minecraft.grug.Grug;
import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.grug.Vec3;

import java.awt.image.BufferedImage;
import java.io.File;

public interface ModLoaderAdapter {
    // --- TODO: Give this section a title, like the other sections below

    File getGameDirectory();

    /** The directory the loaded grug mods live in, which is what the screenshot tree is under. */
    File getGrugModsDirectory();

    /**
     * Which process this is: the client, or a dedicated server.
     *
     * <p>A loader knows without asking, because the game told it. A loader that has no dedicated
     * server at all still answers {@link GrugSide#CLIENT}, and its adapter then has nothing on the
     * server side to disagree with.
     */
    GrugSide getSide();

    /**
     * Refuses a call that needs a client when this process is a dedicated server, and says whether
     * it refused.
     *
     * <p>This is what the four client-only functions are made of: a camera to put somewhere, a
     * window to look through it in, a frame to read back from, and a local player whose right-click
     * the game will route. A dedicated server has none of those, so letting one of them through
     * raises something from inside the game's client classes rather than saying what is wrong.
     *
     * <p>It lives here, on the interface, rather than in {@code HostFunctions} because that is
     * where "which functions need a client" belongs, and because every loader's implementations of
     * those four functions are already outside the coverage gate for being GL glue. A branch in
     * {@code HostFunctions} would have had to be excluded instead, which would have taken the
     * delegation that surrounds it out of measurement with it.
     *
     * <p>A refusal is a host function error rather than a fatal: it names the function, it fails
     * the test that called it, and the game keeps running, which is what a mod's mistake deserves.
     */
    @GrugGenerated("the refusal needs a dedicated server, which no CI run launches until #163")
    default boolean refuseWithoutAClient(String function) {
        GrugSide side = getSide();
        if (side == GrugSide.CLIENT) return false;

        Grug.hostFunctionErrorHappened(
                Grug.statePtr,
                function
                        + ": This needs a client, and grug is running on "
                        + side.description()
                        + ".");
        return true;
    }

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

    /**
     * The level this process's tests build their fixtures in.
     *
     * <p>On a dedicated server that is the server's own level, never a client's: there is no client
     * to ask. On a client it is the client's world, and which world that is depends on the loader,
     * because on 1.20.6 a singleplayer client's world is a copy of the one the integrated server
     * owns and a fixture has to go into the copy that owns it. See each adapter's own answer.
     */
    Object getLevel();

    /**
     * The player a test anchors its fixtures to and drives {@link #openGui} with: the local player
     * on a client, or a connected player on a dedicated server. Null when this side has none yet,
     * which on a dedicated server means nobody has joined.
     */
    Object testPlayer();

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

    /**
     * Closes whatever screen the client currently has open, so a test that captures the world is
     * not looking at a panel an earlier test opened and never closed.
     *
     * <p>Tests share one client for the whole run, so an open screen outlives the test that opened
     * it. That is invisible until a later test takes a screenshot and finds the world behind a
     * panel it did not ask for.
     */
    void closeOpenScreenForTest();

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

    /**
     * Reads the rectangle out of the current frame, or returns null after reporting why it could
     * not: a coordinate outside the frame, or an empty rectangle.
     *
     * <p>Separate from {@link #assertScreenshotEquals} so a test can hold two captures of the same
     * rectangle and compare them with each other, which is how it checks that the game redrew
     * something. Comparing against a reference image instead has to be accepted once per rendering,
     * and the same crop of the same block does not render identically on every version or platform.
     */
    BufferedImage captureScreenshot(double x1, double y1, double x2, double y2);
}
