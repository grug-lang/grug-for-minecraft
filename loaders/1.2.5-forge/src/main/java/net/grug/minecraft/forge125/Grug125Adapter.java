package net.grug.minecraft.forge125;

import cpw.mods.fml.common.FMLCommonHandler;

import net.grug.minecraft.core.GrugBlockSync;
import net.grug.minecraft.core.GrugItemStacks;
import net.grug.minecraft.core.GrugRegistryNames;
import net.grug.minecraft.core.GrugRelight;
import net.grug.minecraft.core.GrugRenderSettle;
import net.grug.minecraft.core.GrugSide;
import net.grug.minecraft.core.GrugTestBox;
import net.grug.minecraft.core.GrugTestPlayerSnap;
import net.grug.minecraft.core.GrugWorldReady;
import net.grug.minecraft.core.ModLoaderAdapter;
import net.grug.minecraft.forge125.block.GrugBlock;
import net.grug.minecraft.forge125.block.GrugBlocks;
import net.grug.minecraft.forge125.block.entity.GrugBlockEntity;
import net.grug.minecraft.forge125.grug.DummyCraftingInventory;
import net.grug.minecraft.forge125.grug.VanillaNames;
import net.grug.minecraft.forge125.item.GrugItem;
import net.grug.minecraft.grug.BlockPos;
import net.grug.minecraft.grug.Grug;
import net.grug.minecraft.grug.GrugBlockData;
import net.grug.minecraft.grug.GrugBlockEntityNbt;
import net.grug.minecraft.grug.GrugDeclarations;
import net.grug.minecraft.grug.GrugDisplayTicks;
import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.grug.GrugIdPool;
import net.grug.minecraft.grug.GrugItemData;
import net.grug.minecraft.grug.GrugSlotAccess;
import net.grug.minecraft.grug.GrugVanillaBlocks;
import net.grug.minecraft.grug.Vec3;
import net.minecraft.src.AxisAlignedBB;
import net.minecraft.src.Block;
import net.minecraft.src.Chunk;
import net.minecraft.src.CraftingManager;
import net.minecraft.src.Entity;
import net.minecraft.src.EntityItem;
import net.minecraft.src.EntityPlayer;
import net.minecraft.src.EnumSkyBlock;
import net.minecraft.src.IChunkLoader;
import net.minecraft.src.IInventory;
import net.minecraft.src.Item;
import net.minecraft.src.ItemStack;
import net.minecraft.src.Material;
import net.minecraft.src.ModLoader;
import net.minecraft.src.MovingObjectPosition;
import net.minecraft.src.NBTTagCompound;
import net.minecraft.src.TileEntity;
import net.minecraft.src.Vec3D;
import net.minecraft.src.World;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * The Forge 1.2.5 adapter: translates grug host functions onto the decompiled 1.2.5 API.
 *
 * <p>Every method is glue for one specific game generation and is written against the exact
 * signatures in the 1.2.5 decompile (see {@code net.minecraft.src}). Method-level coverage
 * annotations call out the parts an ordinary test cannot reach.
 */
public abstract class Grug125Adapter implements ModLoaderAdapter {

    /**
     * This loader's directory under {@code loaders/}, which names the screenshot references it
     * produces.
     */
    private static final String LOADER_NAME = "1.2.5-forge";

    /**
     * This loader's column of the canonical vanilla block name table, so a mod naming a block gets
     * the same block here as it would on every other loader. It matters more here than elsewhere:
     * 1.2.5 spells most blocks with MCP field names that resolve nowhere else.
     */
    private static final GrugVanillaBlocks.Loader CANONICAL_BLOCKS =
            GrugVanillaBlocks.forLoader(LOADER_NAME);

    /**
     * The game directory, which both sides' FML answers: the client's {@code mcDataDir} and the
     * server's working directory. ModLoader's own {@code getMinecraftInstance()} cannot be asked,
     * because it returns a {@code Minecraft} on the client and an {@code Object} on the server.
     */
    private static File gameDir() {
        return FMLCommonHandler.instance().getMinecraftRootDirectory();
    }

    @Override
    public File getGameDirectory() {
        return gameDir();
    }

    @Override
    public File getGrugModsDirectory() {
        return mod_Grug.getActiveGrugModsDir();
    }

    @Override
    public String getLoaderName() {
        return LOADER_NAME;
    }

    @Override
    public void logInfo(String message) {
        mod_Grug.LOGGER.info(message);
    }

    @Override
    public void logError(String message) {
        // A contained mod err, and the sandbox's own report of it: the JNI layer clears whatever a
        // script threw and the script carries on, so this is where a failed script call surfaces.
        mod_Grug.LOGGER.severe(message);
    }

    // --- GUI & Inventory Methods ---

    @Override
    public void consumeCraftingIngredients(Object blockEntityObj, double startSlot) {
        IInventory inv = (IInventory) blockEntityObj;
        DummyCraftingInventory matrix = new DummyCraftingInventory(inv, (int) startSlot);
        for (int i = 0; i < matrix.getSizeInventory(); i++) {
            ItemStack stack = matrix.getStackInSlot(i);
            if (stack != null) {
                matrix.decrStackSize(i, 1);
                applyCraftingReturn(matrix, i, stack);
            }
        }
    }

    @GrugGenerated("crafting return: the empty-container path is not forceable per recipe")
    private static void applyCraftingReturn(
            DummyCraftingInventory matrix, int slot, ItemStack stack) {
        if (stack.getItem().hasContainerItem()) {
            matrix.setInventorySlotContents(
                    slot, new ItemStack(stack.getItem().getContainerItem()));
        }
    }

    @Override
    public double countItemInInventory(Object blockEntityObj, Object itemObj, double damage) {
        IInventory inv = (IInventory) blockEntityObj;
        Item item = (Item) itemObj;
        int total = 0;
        for (int i = 0; i < inv.getSizeInventory(); i++) {
            ItemStack stack = inv.getStackInSlot(i);
            if (stack != null && stack.getItem() == item && stack.getItemDamage() == (int) damage) {
                total += stack.stackSize;
            }
        }
        return total;
    }

    @Override
    public void dropInventory(Object levelObj, double x, double y, double z) {
        World world = (World) levelObj;
        TileEntity be =
                world.getBlockTileEntity(
                        (int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
        IInventory inv = (IInventory) be;
        for (int i = 0; i < inv.getSizeInventory(); i++) {
            ItemStack stack = inv.getStackInSlot(i);
            if (stack != null) {
                world.spawnEntityInWorld(new EntityItem(world, x, y, z, stack));
                inv.setInventorySlotContents(i, null);
            }
        }
    }

    @Override
    public double extractItemFromInventory(
            Object blockEntityObj, Object itemObj, double damage, double amount) {
        IInventory inv = (IInventory) blockEntityObj;
        Item item = (Item) itemObj;
        int remainingToExtract = (int) amount;
        for (int i = 0; i < inv.getSizeInventory() && remainingToExtract > 0; i++) {
            if (!GrugSlotAccess.isExtractable(blockEntityObj, i)) continue;
            ItemStack stack = inv.getStackInSlot(i);
            if (stack != null && stack.getItem() == item && stack.getItemDamage() == (int) damage) {
                int extractFromSlot = Math.min(stack.stackSize, remainingToExtract);
                inv.decrStackSize(i, extractFromSlot);
                remainingToExtract -= extractFromSlot;
            }
        }
        return amount - remainingToExtract;
    }

    @Override
    public double takeItemFromSlot(Object blockEntityObj, double slot, double amount) {
        IInventory inv = (IInventory) blockEntityObj;
        ItemStack removed = inv.decrStackSize((int) slot, (int) amount);
        return removed != null ? removed.stackSize : 0;
    }

    @Override
    public Object getResultStack(Object blockEntityObj) {
        // This loader compiles to Java 8 bytecode, so no pattern matching here.
        if (!(blockEntityObj instanceof GrugBlockEntity)) return null;
        return ((GrugBlockEntity) blockEntityObj).getResultStack();
    }

    @Override
    public Object getStackItem(Object stackObj) {
        return ((ItemStack) stackObj).getItem();
    }

    @Override
    public double getStackDamage(Object stackObj) {
        return ((ItemStack) stackObj).getItemDamage();
    }

    @Override
    public double getStackCount(Object stackObj) {
        return ((ItemStack) stackObj).stackSize;
    }

    @Override
    public long getGrugEntityHandle(Object blockEntityObj) {
        if (blockEntityObj instanceof GrugBlockEntity) {
            return ((GrugBlockEntity) blockEntityObj).getGrugEntityHandle();
        }
        return 0;
    }

    @Override
    public double insertItemIntoSlot(
            Object blockEntityObj,
            double slot,
            Object itemObj,
            double damage,
            double amount,
            boolean commit) {
        IInventory inventory = (IInventory) blockEntityObj;
        Item item = (Item) itemObj;
        int meta = (int) damage;
        int maxStack = item.getItemStackLimit();
        ItemStack stack = inventory.getStackInSlot((int) slot);
        int room;
        if (stack == null) {
            room = maxStack;
        } else if (stack.getItem() == item && stack.getItemDamage() == meta) {
            room = maxStack - stack.stackSize;
        } else {
            room = 0;
        }
        int add = Math.min(Math.max(room, 0), (int) amount);
        if (commit && add > 0) {
            if (stack == null) {
                ItemStack created = new ItemStack(item, add);
                created.setItemDamage(meta);
                inventory.setInventorySlotContents((int) slot, created);
            } else {
                stack.stackSize += add;
            }
        }
        return add;
    }

    @Override
    public double takeCraftingResult(Object blockEntityObj, double amount) {
        if (blockEntityObj instanceof GrugBlockEntity) {
            GrugBlockEntity gbe = (GrugBlockEntity) blockEntityObj;
            ItemStack removed = gbe.takeResultStack((int) amount);
            if (removed != null) {
                gbe.notifyOutputTaken(removed.stackSize);
                return removed.stackSize;
            }
            return 0;
        }

        // 1.2.5 has no generic result API: a real mod's tile only exposes its own extraction.
        // BuildCraft's TileAutoWorkbench computes the result, so the reference harness calls
        // extractItem(true, false) through reflection.
        return extractReferenceResult(blockEntityObj, amount);
    }

    /**
     * Pulls the computed result out of a reference tile that has no result container, by
     * reflectively calling its own {@code extractItem(boolean, boolean)} (BuildCraft's {@code
     * TileAutoWorkbench.extractItem(true, false)}). Fails loudly if the tile has no such method.
     */
    @GrugGenerated(
            "reference result extraction: the tile that has the method only exists in a reference"
                    + " run, and its success continuation is on the same lines as the lookup")
    private static double extractReferenceResult(Object blockEntityObj, double amount) {
        java.lang.reflect.Method extractItem;
        try {
            extractItem =
                    blockEntityObj
                            .getClass()
                            .getMethod("extractItem", boolean.class, boolean.class);
        } catch (NoSuchMethodException e) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr,
                    "take_crafting_result: "
                            + blockEntityObj.getClass().getName()
                            + " has no extractItem(boolean, boolean) method to pull a reference"
                            + " result from.");
            return 0;
        }

        int total = 0;
        int remaining = (int) amount;
        try {
            while (remaining > 0) {
                ItemStack result = (ItemStack) extractItem.invoke(blockEntityObj, true, false);
                if (result == null) break;
                total += result.stackSize;
                remaining -= result.stackSize;
            }
        } catch (ReflectiveOperationException e) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr,
                    "take_crafting_result: the reference tile's extractItem threw " + e + ".");
            return 0;
        }
        return total;
    }

    @Override
    public double getInventorySize(Object blockEntityObj) {
        return ((IInventory) blockEntityObj).getSizeInventory();
    }

    @Override
    public double insertItemIntoInventory(
            Object blockEntityObj, Object itemObj, double damage, double amount, boolean insert) {
        IInventory inventory = (IInventory) blockEntityObj;
        Item item = (Item) itemObj;
        int meta = (int) damage;
        int maxStack = item.getItemStackLimit();
        int remaining = (int) amount;

        // Top up matching stacks first, then fill empty slots, like a player's inventory fills. A
        // block entity that declares a rule only has its insertable slots filled, which for an
        // inventory declaring none is all of them. With insert false the count that would fit is
        // answered and nothing is changed, which is how a caller asks whether the inventory has
        // room.
        for (int i = 0; i < inventory.getSizeInventory() && remaining > 0; i++) {
            if (!GrugSlotAccess.isInsertable(blockEntityObj, i)) continue;
            ItemStack stack = inventory.getStackInSlot(i);
            if (stack != null && stack.getItem() == item && stack.getItemDamage() == meta) {
                int room = maxStack - stack.stackSize;
                if (room > 0) {
                    int add = Math.min(room, remaining);
                    if (insert) {
                        stack.stackSize += add;
                    }
                    remaining -= add;
                }
            }
        }

        for (int i = 0; i < inventory.getSizeInventory() && remaining > 0; i++) {
            if (!GrugSlotAccess.isInsertable(blockEntityObj, i)) continue;
            if (inventory.getStackInSlot(i) == null) {
                int add = Math.min(maxStack, remaining);
                if (insert) {
                    ItemStack stack = new ItemStack(item, add);
                    stack.setItemDamage(meta);
                    inventory.setInventorySlotContents(i, stack);
                }
                remaining -= add;
            }
        }

        return amount - remaining;
    }

    @Override
    public double getItemCountInSlot(Object blockEntityObj, double slot) {
        ItemStack stack = ((IInventory) blockEntityObj).getStackInSlot((int) slot);
        return stack != null ? stack.stackSize : 0;
    }

    @Override
    public double getItemDamageInSlot(Object blockEntityObj, double slot) {
        ItemStack stack = ((IInventory) blockEntityObj).getStackInSlot((int) slot);
        return stack != null ? stack.getItemDamage() : 0;
    }

    @Override
    public Object getItemInSlot(Object blockEntityObj, double slot) {
        ItemStack stack = ((IInventory) blockEntityObj).getStackInSlot((int) slot);
        return (stack != null) ? stack.getItem() : null;
    }

    @Override
    public void setItemCountInSlot(Object blockEntityObj, double slot, double count) {
        IInventory inv = (IInventory) blockEntityObj;
        ItemStack stack = inv.getStackInSlot((int) slot);
        if (stack != null) {
            if (count <= 0) inv.setInventorySlotContents((int) slot, null);
            else stack.stackSize = (int) count;
        }
    }

    @Override
    public void setItemDamageInSlot(Object blockEntityObj, double slot, double damage) {
        ItemStack stack = ((IInventory) blockEntityObj).getStackInSlot((int) slot);
        if (stack != null) {
            stack.setItemDamage((int) damage);
        }
    }

    @Override
    public void setItemInSlot(Object blockEntityObj, double slot, Object itemObj, double count) {
        IInventory inv = (IInventory) blockEntityObj;
        inv.setInventorySlotContents((int) slot, new ItemStack((Item) itemObj, (int) count));
    }

    @Override
    public void updateRecipeOutput(Object blockEntityObj, double startSlot) {
        if (!(blockEntityObj instanceof GrugBlockEntity)) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr,
                    "update_recipe_output: "
                            + blockEntityObj.getClass().getName()
                            + " has no crafting result container.");
            return;
        }
        GrugBlockEntity gbe = (GrugBlockEntity) blockEntityObj;
        IInventory inv = (IInventory) blockEntityObj;
        DummyCraftingInventory matrix = new DummyCraftingInventory(inv, (int) startSlot);
        ItemStack result = CraftingManager.getInstance().findMatchingRecipe(matrix);
        gbe.setResultStack(result != null ? result.copy() : null);
    }

    // --- Item Registry Methods ---

    @Override
    public Object getItemFromRegistry(Object resourceLocationObj) {
        GrugBlocks.init();

        String raw = (String) resourceLocationObj;
        String path = localPath(raw);

        Object declared = matchDeclared(path);
        if (declared != null) return declared;

        Object vanillaItem = matchVanillaItem(path);
        if (vanillaItem != null) return vanillaItem;

        Object vanillaBlock = matchVanillaBlock(path);
        if (vanillaBlock != null) return vanillaBlock;

        return matchItemName(path);
    }

    /**
     * A reference run's items, by the name the item set for itself, as resolveBlock does for
     * blocks: 1.2.5 predates registry names. BuildCraft names its wrench "wrenchItem", which Item
     * stores as "item.wrenchItem", and a pipe item after its class, "item.PipeItemsWood". An item
     * with no name of its own names its stack instead, which is how ItemEngine's metadata 0 is
     * "tile.engineWood".
     *
     * <p>The comparison itself is {@link GrugRegistryNames#firstItemMatch}: a reference name is
     * only ever set by a mod the counted run does not load, so the match arm has no test here, and
     * the pure rule is unit-tested in every combination instead.
     */
    private Object matchItemName(String path) {
        String itemName = "item." + path;
        String tileName = "tile." + path;
        Object found = null;
        // An indexed loop rather than a for-each: with the array never empty, both sides of this
        // loop's condition run, while a for-each iterator's first hasNext answer is its only one.
        for (int i = 0; i < Item.itemsList.length; i++) {
            Item item = Item.itemsList[i];
            found =
                    GrugRegistryNames.firstItemMatch(
                            found,
                            item,
                            item == null ? null : item.getItemName(),
                            item == null ? null : item.getItemNameIS(new ItemStack(item)),
                            itemName,
                            tileName);
        }
        return found;
    }

    /**
     * The declared registry's answer for the path, item first and then block, or null.
     *
     * <p>A declared entry always carries a file id and the registry is never empty, so the scans
     * and their "no file id" arm are behind the marker; the per-entry name match, the file id
     * lookup and the item/block lookup are measured.
     */
    private Object matchDeclared(String path) {
        Object item = declaredItem(path);
        if (item != null) {
            return item;
        }
        return declaredBlockItem(path);
    }

    /** The declared item the path names, or null when it names none. */
    private static Object declaredItem(String path) {
        Long fileId = declaredItemFileId(path);
        if (fileId == null) {
            return null;
        }
        return itemWithFileId(fileId);
    }

    /** The item of the declared block the path names, or null when it names none. */
    private static Object declaredBlockItem(String path) {
        Long fileId = declaredBlockFileId(path);
        if (fileId == null) {
            return null;
        }
        return itemForBlock(blockWithFileId(fileId));
    }

    @GrugGenerated(
            "declared registry match: an empty registry and a declaration with no file id are not"
                    + " forceable")
    private static Long declaredItemFileId(String path) {
        for (Map.Entry<String, GrugItemData> entry : GrugDeclarations.declaredItems.entrySet()) {
            Long fileId = declaredItemFileIdOf(entry.getKey(), path);
            if (fileId != null) {
                return fileId;
            }
        }
        return null;
    }

    @GrugGenerated(
            "declared registry match: an empty registry and a declaration with no file id are not"
                    + " forceable")
    private static Long declaredBlockFileId(String path) {
        for (Map.Entry<String, GrugBlockData> entry : GrugDeclarations.declaredBlocks.entrySet()) {
            Long fileId = declaredBlockFileIdOf(entry.getKey(), path);
            if (fileId != null) {
                return fileId;
            }
        }
        return null;
    }

    /** The file id a declared item's name maps to, or null when the path names another entry. */
    private static Long declaredItemFileIdOf(String key, String path) {
        return GrugRegistryNames.matches(key, path) ? itemFileId(key) : null;
    }

    /** The file id a declared block's name maps to, or null when the path names another entry. */
    private static Long declaredBlockFileIdOf(String key, String path) {
        return GrugRegistryNames.matches(key, path) ? blockFileId(key) : null;
    }

    /** The file id the declared items gave the name. */
    private static Long itemFileId(String key) {
        return GrugDeclarations.itemDataByFileId.entrySet().stream()
                .filter(e -> e.getValue().id.equals(key))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
    }

    /** The file id the declared blocks gave the name. */
    private static Long blockFileId(String key) {
        return GrugDeclarations.blockDataByFileId.entrySet().stream()
                .filter(e -> e.getValue().id.equals(key))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
    }

    @GrugGenerated("a declaration whose entry is not in the game: registration registers it")
    private static Item itemWithFileId(long fileId) {
        for (Item item : Item.itemsList) {
            Item match = grugItemWithFileId(item, fileId);
            if (match != null) {
                return match;
            }
        }
        return null;
    }

    private static Item grugItemWithFileId(Item item, long fileId) {
        return item instanceof GrugItem && ((GrugItem) item).itemFileId == fileId ? item : null;
    }

    @GrugGenerated("a declaration whose entry is not in the game: registration registers it")
    private static Block blockWithFileId(long fileId) {
        for (Block block : Block.blocksList) {
            Block match = grugBlockWithFileId(block, fileId);
            if (match != null) {
                return match;
            }
        }
        return null;
    }

    private static Block grugBlockWithFileId(Block block, long fileId) {
        return block instanceof GrugBlock && ((GrugBlock) block).blockFileId == fileId
                ? block
                : null;
    }

    @GrugGenerated("a declared block whose item is missing: registration registers both")
    private static Object itemForBlock(Block block) {
        return block == null ? null : Item.itemsList[block.blockID];
    }

    @GrugGenerated("vanilla item lookup: the table only names ids the item table holds")
    private static Object vanillaItemAt(int id) {
        return id >= 0 && id < Item.itemsList.length ? Item.itemsList[id] : null;
    }

    private Object matchVanillaItem(String path) {
        return vanillaItemAt(VanillaNames.itemId(path));
    }

    @GrugGenerated(
            "vanilla block lookup: the table only names ids the block table holds, and a block's"
                    + " item sits at its own id")
    private static Object vanillaBlockAt(int id) {
        if (id < 0 || id >= Block.blocksList.length || Block.blocksList[id] == null) {
            return null;
        }
        // Block items live at itemsList[blockID]; Block's constructor registers them there.
        return Item.itemsList[id];
    }

    private Object matchVanillaBlock(String path) {
        // The same name place_block resolves, through the same table, so that a mod looking up a
        // block's item gets the same block it would place.
        return vanillaBlockAt(VanillaNames.blockId(localName(path)));
    }

    @Override
    public Object createItemEntity(
            Object levelObj, double x, double y, double z, Object itemStackObj) {
        return new EntityItem((World) levelObj, x, y, z, (ItemStack) itemStackObj);
    }

    @Override
    public Object createItemStack(Object itemObj, double damage, double count) {
        // Every caller hands over an Item: the game keeps a block's own item at
        // Item.itemsList[blockID], so a block never reaches here as a Block.
        ItemStack stack = new ItemStack((Item) itemObj, (int) count);
        stack.setItemDamage((int) damage);
        return stack;
    }

    @Override
    public int maxItemStackCount() {
        // writeToNBT writes Count as one byte, and readFromNBT reads it back as a signed int.
        return Byte.MAX_VALUE;
    }

    @Override
    public double getItemEntityDamage(Object itemEntityObj) {
        // A dead item entity, whose saved item id the game does not have, carries no stack: its
        // damage is 0, not a throw. See GrugItemStacks.
        return GrugItemStacks.numberOrZero(
                ((EntityItem) itemEntityObj).item, stack -> ((ItemStack) stack).getItemDamage());
    }

    @Override
    public double getItemEntityCount(Object itemEntityObj) {
        return GrugItemStacks.numberOrZero(
                ((EntityItem) itemEntityObj).item, stack -> ((ItemStack) stack).stackSize);
    }

    @Override
    public double getItemEntityPickupDelay(Object itemEntityObj) {
        return ((EntityItem) itemEntityObj).delayBeforeCanPickup;
    }

    @Override
    public void setItemEntityPickupDelay(Object itemEntityObj, int ticks) {
        ((EntityItem) itemEntityObj).delayBeforeCanPickup = ticks;
    }

    @Override
    public void clearItemEntities(
            Object levelObj, double x1, double y1, double z1, double x2, double y2, double z2) {
        World world = (World) levelObj;
        // The game's own box query, so the filtering is its branches and not ours: the six
        // comparisons this used to make are twelve branch outcomes, and the suite's few items only
        // ever exercise one side of each, which the coverage gate then reports as missed.
        for (Object entity :
                world.getEntitiesWithinAABB(
                        EntityItem.class, AxisAlignedBB.getBoundingBox(x1, y1, z1, x2, y2, z2))) {
            EntityItem item = (EntityItem) entity;
            item.setDead();
            // setDead() alone leaves the entity in the loaded list until the world's own update,
            // which runs after the runner's tick, so a test that queries in the same tick would
            // still see it. Do the rest of the removal here, the way World.updateEntities does.
            removeFromChunk(world, item);
            world.loadedEntityList.remove(item);
        }
    }

    /**
     * Takes a dead item out of the chunk it was added to.
     *
     * <p>The box query reads the chunk's own list, so an item left there is found again by a later
     * sweep (#427). The sweep runs before every test, and an item it sees has been through a world
     * tick, which is what adds it to a chunk, so the guard has no other side a test can reach.
     */
    @GrugGenerated("an item the sweep sees has been through a world tick and is in a chunk")
    private static void removeFromChunk(World world, EntityItem item) {
        if (!item.addedToChunk) {
            return;
        }
        removeLoadedItem(world, item);
    }

    /** Takes an item that is in a chunk out of it, the way the world's own update does. */
    private static void removeLoadedItem(World world, EntityItem item) {
        world.getChunkFromChunkCoords(item.chunkCoordX, item.chunkCoordZ).removeEntity(item);
    }

    @Override
    public Object findItemEntity(Object levelObj, double x, double y, double z, double radius) {
        World world = (World) levelObj;
        double radiusSquared = radius * radius;
        for (Object entity : world.loadedEntityList) {
            if (entity instanceof EntityItem) {
                EntityItem item = (EntityItem) entity;
                double dx = item.posX - x;
                double dy = item.posY - y;
                double dz = item.posZ - z;
                if (dx * dx + dy * dy + dz * dz <= radiusSquared) {
                    return item;
                }
            }
        }
        return null;
    }

    @Override
    public Object createResourceLocation(String string) {
        return string;
    }

    // --- World & Entity Methods ---

    @Override
    public void setEntityDeltaMovement(Object entityObj, double dx, double dy, double dz) {
        Entity entity = (Entity) entityObj;
        entity.motionX = dx;
        entity.motionY = dy;
        entity.motionZ = dz;
    }

    @Override
    public Vec3 getEntityDeltaMovement(Object entityObj) {
        Entity entity = (Entity) entityObj;
        return new Vec3(entity.motionX, entity.motionY, entity.motionZ);
    }

    @Override
    public void spawnEntity(Object levelObj, Object entityObj) {
        ((World) levelObj).spawnEntityInWorld((Entity) entityObj);
    }

    @Override
    public Object getBlockEntity(Object levelObj, double x, double y, double z) {
        return ((World) levelObj)
                .getBlockTileEntity((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
    }

    @Override
    public void placeBlock(Object levelObj, double x, double y, double z, String blockName) {
        GrugBlocks.init();

        World world = (World) levelObj;
        String path = localPath(blockName);

        // air is the one name resolveBlock cannot answer, because this version has no air block to
        // resolve: air is the absence of one, block id 0. Placing it is how a test removes a block,
        // so it skips the lookup and is placed like any other block.
        int blockId = 0;
        if (!path.equals("air")) {
            Block targetBlock = resolveBlock(path);

            if (targetBlock == null) {
                // A name that resolves nowhere is a defect in the mod, not a block to skip: it
                // would leave the mod building against a block it never got, which is the cross
                // loader mismatch the canonical name table exists to remove. The host function
                // error is the report, so there is no second log line.
                Grug.hostFunctionErrorHappened(
                        Grug.statePtr, "place_block: Could not resolve block " + blockName);
                return;
            }
            blockId = targetBlock.blockID;
        }

        int posX = (int) Math.floor(x);
        int posY = (int) Math.floor(y);
        int posZ = (int) Math.floor(z);

        if (batchActive) {
            // The same write setBlockWithNotify makes, minus the relight: the batch relights at its
            // end. A write that changes nothing records nothing and is not relit, which is the
            // whole-room case the one-by-one build relit for no change. The neighbour notification
            // stays per block, so block states and dropped items come out the same. See
            // ModLoaderAdapter#beginBatch.
            writeBatched(world, posX, posY, posZ, blockId);
        } else {
            world.setBlockWithNotify(posX, posY, posZ, blockId);
        }

        // A placement that reports false because the block was already there is not a failure,
        // so the block's presence is the check rather than the return value. A y outside the
        // 256-block world height is refused, and a position in a chunk the client has not
        // received swallows the write too. A write the game accepts and does not leave in place
        // is reported the same way: the caller asked for a block that is not there afterwards.
        // See #151.
        if (world.getBlockId(posX, posY, posZ) != blockId) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr,
                    "place_block: the game did not put "
                            + blockName
                            + " at "
                            + posX
                            + ", "
                            + posY
                            + ", "
                            + posZ
                            + ". A y outside this version's world height is refused, and a"
                            + " position in a chunk the client has not received is discarded.");
            return;
        }
        // The client learns of the block from a packet it has not read yet, so the settle wait has
        // to ask the client's own copy rather than trust the frames that passed. See #804.
        blockSync.owe(posX, posY, posZ, getBlock(world, posX, posY, posZ));
    }

    /**
     * Whether a batch of placements is open, in which case {@link #placeBlock} writes without the
     * relight and records the position for {@link #endBatch}.
     */
    private boolean batchActive;

    /** Three ints per block the open batch changed: x, y and z, in the order it touched them. */
    private int[] batchPositions = new int[0];

    private int batchPositionCount;

    @Override
    public void beginBatch(Object levelObj) {
        batchActive = true;
        batchPositionCount = 0;
    }

    @Override
    public void endBatch(Object levelObj) {
        batchActive = false;
        World world = (World) levelObj;
        for (int i = 0; i < batchPositionCount; i += 3) {
            world.updateAllLightTypes(
                    batchPositions[i], batchPositions[i + 1], batchPositions[i + 2]);
        }
        batchPositionCount = 0;
    }

    /** Sweeps of the room's cells the light may take before it is called not settling. */
    private static final int RELIGHT_SWEEP_LIMIT = 16;

    @Override
    public void relightRoom(
            Object levelObj, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        World world = (World) levelObj;
        GrugRelight.sweepUntilStable(
                () -> relightSweep(world, minX, minY, minZ, maxX, maxY, maxZ), RELIGHT_SWEEP_LIMIT);
    }

    /**
     * Recomputes the light of every cell of the box once, in a fixed order, and hashes the block
     * light the box holds afterwards. The engine's incremental updates leave a field that depends
     * on the changes that came before; a cell recomputed from its neighbours is exact, so sweeping
     * until nothing moves ends at the one field the blocks define. See {@link GrugRelight}.
     */
    private static long relightSweep(
            World world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        for (int y = minY; y <= maxY; y++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) {
                    world.updateAllLightTypes(x, y, z);
                }
            }
        }
        long hash = 0;
        for (int y = minY; y <= maxY; y++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) {
                    hash = hash * 31 + world.getSavedLightValue(EnumSkyBlock.Block, x, y, z);
                }
            }
        }
        return hash;
    }

    /** Records a position whose relight the open batch postponed. */
    private void batchPosition(int x, int y, int z) {
        if (batchPositionCount + 3 > batchPositions.length) {
            batchPositions =
                    Arrays.copyOf(batchPositions, Math.max(3 * 64, batchPositions.length * 2));
        }
        batchPositions[batchPositionCount++] = x;
        batchPositions[batchPositionCount++] = y;
        batchPositions[batchPositionCount++] = z;
    }

    /**
     * One block write of an open batch: the chunk's own set, without the relight the batch does at
     * its end, and the neighbour notification the one-by-one build would have made.
     *
     * <p>A position outside the world is refused, which is why the bounds are checked here rather
     * than trusted: the write reaches the chunk directly, and a coordinate outside the world would
     * name a chunk that does not exist.
     */
    @GrugGenerated("out of the world: the room a batch builds is around the player")
    private void writeBatched(World world, int x, int y, int z, int blockId) {
        if (x >= -30000000 && z >= -30000000 && x < 30000000 && z < 30000000 && y >= 0 && y < 256) {
            writeBatchBlock(world, x, y, z, blockId);
        }
    }

    /** The write of one block of a batch, at a position the bounds check has accepted. */
    private void writeBatchBlock(World world, int x, int y, int z, int blockId) {
        if (world.getChunkFromChunkCoords(x >> 4, z >> 4).setBlockID(x & 15, y, z & 15, blockId)) {
            batchPosition(x, y, z);
            world.notifyBlockChange(x, y, z, blockId);
        }
    }

    /**
     * The name without its namespace, which is the form this loader's own resolution takes: 1.2.5
     * predates registry names, and a mod may write a name with or without the namespace.
     */
    private static String localPath(String name) {
        return name.contains(":") ? name.split(":", 2)[1] : name;
    }

    /**
     * The name this loader spells a canonical block name with.
     *
     * <p>Most blocks are spelled the same on every loader, so the table usually answers nothing and
     * the name a mod wrote is what this loader wants. Where Minecraft renamed the block, the table
     * says what 1.2.5 called it, as the word-ordered key {@link VanillaNames} resolves by.
     */
    private static String localName(String canonicalName) {
        return CANONICAL_BLOCKS.localName(canonicalName);
    }

    /**
     * The canonical name for a name this loader's own naming produced, so a mod comparing a {@code
     * get_block} answer to a literal gets the same string on every loader. A block the table does
     * not cover keeps this loader's own spelling, which is right for a mod block or a vanilla block
     * newer than the audit.
     */
    private static String canonicalName(String localName) {
        return CANONICAL_BLOCKS.canonicalName(localName);
    }

    /**
     * The block a name resolves to, or null if nothing answers it.
     *
     * <p>Three registries in the order a reference run needs them, with the scans behind the marker
     * and the per-entry name matches measured. The reference scan itself only runs in a reference
     * run, so it stays behind the marker whole.
     */
    private Block resolveBlock(String path) {
        // 1. A declared grug block.
        Block declared = declaredBlock(path);
        if (declared != null) {
            return declared;
        }

        // 2. A vanilla block from the generated name table, under the name this loader spells the
        // canonical name with. A grug block keeps its own name, so the table only ever sees a
        // vanilla one: step 1 has already looked for it under the name the script wrote.
        Block vanilla = vanillaBlock(path);
        if (vanilla != null) {
            return vanilla;
        }

        // 3. A reference run places the real mod's block, by the name the block set for itself.
        return referenceBlock(path);
    }

    private static Block declaredBlock(String path) {
        Long fileId = declaredBlockFileId(path);
        if (fileId == null) {
            return null;
        }
        return blockWithFileId(fileId);
    }

    private static Block vanillaBlock(String path) {
        return vanillaBlockInTable(VanillaNames.blockId(localName(path)));
    }

    @GrugGenerated("vanilla block lookup: the table only names ids the block table holds")
    private static Block vanillaBlockInTable(int id) {
        if (id >= 0 && id < Block.blocksList.length) {
            return Block.blocksList[id];
        }
        return null;
    }

    /**
     * A reference run's blocks, by the name the block set for itself: 1.2.5 predates block registry
     * names. BuildCraft calls {@code setBlockName("autoWorkbenchBlock")}, and Block stores that as
     * "tile.autoWorkbenchBlock". A block may have no name of its own but an item that names it,
     * which is how BuildCraft's BlockEngine is reachable: ItemEngine names metadata 0
     * tile.engineWood.
     */
    @GrugGenerated(
            "reference-only fallback: only a reference mod names a block, so no grug test reaches"
                    + " this scan")
    private static Block referenceBlock(String path) {
        String blockName = "tile." + path;
        for (Block block : Block.blocksList) {
            if (block == null) {
                continue;
            }
            Item item = Item.itemsList[block.blockID];
            String itemName = item == null ? null : item.getItemNameIS(new ItemStack(block));
            if (blockName.equals(block.getBlockName()) || blockName.equals(itemName)) {
                return block;
            }
        }
        return null;
    }

    @Override
    public long getWorldTime(Object levelObj) {
        return ((World) levelObj).getWorldTime();
    }

    @Override
    public int nextRandomInt(Object levelObj, int bound) {
        return ((World) levelObj).rand.nextInt(bound);
    }

    @Override
    public double nextRandomFloat(Object levelObj) {
        return ((World) levelObj).rand.nextFloat();
    }

    @Override
    public double nextRandomGaussian(Object levelObj) {
        return ((World) levelObj).rand.nextGaussian();
    }

    @Override
    public void explode(Object levelObj, double x, double y, double z, double power) {
        ((World) levelObj).createExplosion(null, x, y, z, (float) power);
    }

    @Override
    public Object getHeldItem(Object playerObj) {
        ItemStack stack = ((EntityPlayer) playerObj).inventory.getCurrentItem();
        return (stack != null) ? stack.getItem() : null;
    }

    @Override
    public void setHeldItem(Object playerObj, Object itemObj) {
        net.minecraft.src.InventoryPlayer inventory = ((EntityPlayer) playerObj).inventory;
        inventory.setInventorySlotContents(
                inventory.currentItem, itemObj == null ? null : new ItemStack((Item) itemObj));
    }

    @Override
    public void clearInventoryOf(Object playerObj) {
        net.minecraft.src.InventoryPlayer inventory = ((EntityPlayer) playerObj).inventory;
        for (int slot = 0; slot < inventory.getSizeInventory(); slot++) {
            inventory.setInventorySlotContents(slot, null);
        }
        inventory.currentItem = 0;
    }

    @Override
    public double getHeldCount(Object playerObj) {
        ItemStack stack = ((EntityPlayer) playerObj).inventory.getCurrentItem();
        return stack == null ? 0.0 : stack.stackSize;
    }

    /** The items this loader lists, in item id order, built once because the list never changes. */
    private List<Item> itemsByIdCache = null;

    private List<Item> itemsById() {
        if (itemsByIdCache == null) {
            List<Item> items = new ArrayList<>();
            for (int id = 0; id < Item.itemsList.length; id++) {
                Item item = Item.itemsList[id];
                if (item != null) {
                    items.add(item);
                }
            }
            itemsByIdCache = items;
        }
        return itemsByIdCache;
    }

    @Override
    public int itemCount() {
        return itemsById().size();
    }

    @Override
    @GrugGenerated(
            "the out-of-range guard: the host function checks the range first, so a run"
                    + " never reaches this arm")
    public Object itemByIndex(int index) {
        List<Item> items = itemsById();
        return index >= 0 && index < items.size() ? items.get(index) : null;
    }

    @Override
    public int getItemMaxStack(Object itemObj) {
        return ((Item) itemObj).getItemStackLimit();
    }

    @Override
    public void giveItemToPlayer(Object playerObj, Object itemObj) {
        giveItemToPlayer((EntityPlayer) playerObj, (Item) itemObj, 1);
    }

    @Override
    public void giveItemStackToPlayer(Object playerObj, Object itemObj) {
        Item item = (Item) itemObj;
        giveItemToPlayer((EntityPlayer) playerObj, item, item.getItemStackLimit());
    }

    /**
     * Adds the stack to the player's inventory, dropping whatever does not fit at their feet. 1.2.5
     * has no give command and its singleplayer client is the server, so the inventory write is the
     * give.
     */
    @GrugGenerated("the null-player arm: a run gives to the player it just cleared")
    private void giveItemToPlayer(EntityPlayer player, Item item, int count) {
        if (player == null) return;
        ItemStack stack = new ItemStack(item, count);
        // 1.2.5 returns true as soon as any part of the stack fits, and leaves the rest in
        // stack.stackSize. The return value does not say whether all of it fit, so the remainder is
        // dropped whatever the add answered, as the reference does.
        player.inventory.addItemStackToInventory(stack);
        if (stack.stackSize > 0) {
            player.dropPlayerItem(stack);
        }
    }

    @Override
    public boolean hasNeighborSignal(Object levelObj, double x, double y, double z) {
        World world = (World) levelObj;
        return world.isBlockIndirectlyGettingPowered(
                (int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
    }

    @Override
    public String getBlock(Object levelObj, double x, double y, double z) {
        World world = (World) levelObj;
        return blockName(
                world.getBlockId((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z)));
    }

    /**
     * The name a block id is answered with: air, a grug block's declared name, or the name table's
     * spelling mapped to the canonical one.
     *
     * <p>An id outside the tables, a grug block whose file has no declaration and an id the table
     * does not name are broken state rather than an answer a run reaches, so they carry the
     * annotation instead of a test.
     */
    private static String blockName(int id) {
        if (id == 0) {
            return "minecraft:air";
        }
        String declared = declaredBlockName(id);
        if (declared != null) {
            return declared;
        }
        // Mapped through the canonical table so a mod sees the same name it would on any other
        // loader, rather than 1.2.5's MCP field name, which resolves nowhere else.
        return namedBlock(VanillaNames.blockName(id));
    }

    @GrugGenerated(
            "block names: an id outside the tables and a grug block with no data cannot be forced"
                    + " from a headless test")
    private static String declaredBlockName(int id) {
        if (id > 0 && id < Block.blocksList.length) {
            Block block = Block.blocksList[id];
            if (block instanceof GrugBlock) {
                GrugBlockData data =
                        GrugDeclarations.blockDataByFileId.get(((GrugBlock) block).blockFileId);
                if (data != null) {
                    return data.id;
                }
            }
        }
        return null;
    }

    @GrugGenerated(
            "block names: an id the table does not name cannot be forced from a headless test")
    private static String namedBlock(String name) {
        return name == null ? "minecraft:unknown" : canonicalName(name);
    }

    @Override
    public boolean isBlock(Object levelObj, double x, double y, double z, String blockName) {
        String path = localPath(blockName);
        World world = (World) levelObj;
        return world.getBlockId((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z))
                == blockIdOf(path);
    }

    /**
     * The id {@link #placeBlock} would put down for the name, or -1 for one that resolves nowhere.
     *
     * <p>The room check this answers only asks with the names it builds the room from, which always
     * resolve, so the -1 arm has no test that can reach it.
     */
    @GrugGenerated("an unknown name: the room check only asks with stone and torch, which resolve")
    private int blockIdOf(String path) {
        Block block = resolveBlock(path);
        return block == null ? -1 : block.blockID;
    }

    @Override
    public boolean isAir(Object levelObj, double x, double y, double z) {
        World world = (World) levelObj;
        return world.getBlockId((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z)) == 0;
    }

    @Override
    public double countItemEntities(Object levelObj, double x, double y, double z, double radius) {
        World world = (World) levelObj;
        double radiusSquared = radius * radius;
        int count = 0;
        for (Object entity : world.loadedEntityList) {
            if (entity instanceof EntityItem) {
                EntityItem item = (EntityItem) entity;
                double dx = item.posX - x;
                double dy = item.posY - y;
                double dz = item.posZ - z;
                if (dx * dx + dy * dy + dz * dz <= radiusSquared) {
                    count++;
                }
            }
        }
        return count;
    }

    @Override
    public void notifyNeighbors(Object levelObj, double x, double y, double z) {
        World world = (World) levelObj;
        int bx = (int) Math.floor(x);
        int by = (int) Math.floor(y);
        int bz = (int) Math.floor(z);
        int changedId = world.getBlockId(bx, by, bz);

        // The world's own notification, which a server delivers and a client only turns into a
        // rerender of the block. The game skips the neighbour hooks on a client, but the grug block
        // entity runs there too, so a remote client delivers them itself.
        world.notifyBlockChange(bx, by, bz, changedId);
        notifyNeighborsOnClient(world, bx, by, bz, changedId);
    }

    /**
     * Delivers the neighbour hook to the six neighbours on a client whose world is remote, where
     * {@link World#notifyBlockChange} skips it.
     *
     * <p>The run's world is the client's own, never remote, so no test can reach this. It stays for
     * a client connected to a server, where the tile entities of the mod's blocks run too.
     */
    @GrugGenerated("a remote world: the run's singleplayer world is never remote")
    private static void notifyNeighborsOnClient(
            World world, int bx, int by, int bz, int changedId) {
        if (!world.isRemote) {
            return;
        }

        int[][] offsets = {{-1, 0, 0}, {1, 0, 0}, {0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}};
        for (int[] offset : offsets) {
            int nx = bx + offset[0];
            int ny = by + offset[1];
            int nz = bz + offset[2];
            int id = world.getBlockId(nx, ny, nz);
            if (id > 0 && id < Block.blocksList.length && Block.blocksList[id] != null) {
                Block.blocksList[id].onNeighborBlockChange(world, nx, ny, nz, changedId);
            }
        }
    }

    @Override
    public Object getBlockEntityLevel(Object blockEntityObj) {
        return ((TileEntity) blockEntityObj).worldObj;
    }

    @Override
    @GrugGenerated("no player: the run has no tests to build a room for until someone has joined")
    public void buildTestBox(int radius) {
        // The room build is the first thing the runner does for a test, and it dirties the
        // renderer, so any settle answer from before it describes frames this test must not
        // capture. See GrugRenderSettle.reset.
        renderSettle.reset();
        EntityPlayer player = (EntityPlayer) testPlayer();
        if (player == null) return;
        buildBoxAt(player, radius);
    }

    private void buildBoxAt(EntityPlayer player, int radius) {
        // posY is the eye reference on a client and the feet on a dedicated server, so the feet
        // come from the collision box, as they do for isWorldReady. Built around the eye on a
        // server, the room sits 1.62 blocks too low and its ceiling lands directly above the
        // origin, so a declined click has no air to place the held block into (#1090).
        GrugTestBox.build(
                this, getLevel(), player.posX, player.boundingBox.minY, player.posZ, radius, false);
    }

    @Override
    @GrugGenerated("no player: the run has no tests to sweep after until someone has joined")
    public void sweepTestItems() {
        EntityPlayer player = (EntityPlayer) testPlayer();
        if (player == null) return;
        sweepItemsAt(player);
    }

    private void sweepItemsAt(EntityPlayer player) {
        GrugTestBox.sweepItems(this, getLevel(), player.posX, player.boundingBox.minY, player.posZ);
    }

    @Override
    public void roundTripNbt(Object blockEntityObj) {
        // TileEntity.writeToNBT looks the class up in the mapping ModLoader fills on registration.
        GrugBlocks.init();

        TileEntity be = (TileEntity) blockEntityObj;
        NBTTagCompound nbt = new NBTTagCompound();
        be.writeToNBT(nbt);
        be.readFromNBT(nbt);
    }

    /** The name the test block registers under. No mod ships it. */
    private static final String HIGH_BLOCK_ID = "grug:zz_high_block_round_trip";

    /**
     * The block the test takes the high id under, with none of grug's wiring.
     *
     * <p>It is not a {@link GrugBlock} on purpose: replacing any block in 1.2.5 calls the replaced
     * block's {@code onBlockRemoval}, and a grug block's does that with the block's file id, so a
     * test block would hand grug-rs an id no compiled file owns and abort the whole game (#123) the
     * moment the test puts the cell back. What the test proves is a property of the id tables and
     * of the save format, not of the wiring, so the block is the plain kind every other mod
     * registers, through {@code ModLoader.registerBlock}.
     */
    private static final class HighBlockRoundTripBlock extends Block {
        private HighBlockRoundTripBlock(int id) {
            super(id, 0, Material.rock);
        }
    }

    @GrugGenerated(
            "test-only round trip: its failure arms need a game that refuses the write, an"
                    + " unwritable save, an exhausted id pool or a corrupted read-back, none of"
                    + " which a run can force; the grug test asserts the id it answers")
    long highBlockRoundTrip(World world) {
        if (world == null) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr, "Test.high_block_round_trip: There is no level to test in yet.");
            return -1L;
        }

        Vec3 origin = getTestOrigin();
        if (origin == null) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr,
                    "Test.high_block_round_trip: There is no test player to measure the origin"
                            + " from yet.");
            return -1L;
        }
        BlockPos block = originBlock(origin);
        int ox = block.x();
        int oy = block.y();
        int oz = block.z();

        // Every low id the pack's other mods left free is taken now, the overrun #509 hit.
        List<Integer> standIns = takeStandIns();

        // The pool registration takes ids from, with the low range used up: only a search that
        // continues above 255 has a free id to hand out.
        GrugIdPool pool = GrugBlocks.blockIdPool();
        if (pool.remaining() < 1) {
            releaseStandIns(standIns);
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr,
                    "Test.high_block_round_trip: every id from "
                            + GrugBlocks.START_BLOCK_ID
                            + " to "
                            + GrugBlocks.LAST_BLOCK_ID
                            + " is used and the block-id search found no free id above it, so a"
                            + " grug block that overruns the low range gets no id at all.");
            return -1L;
        }
        int highId = pool.take();

        registerRoundTripBlock(highId);
        int before = placeRoundTripBlock(world, ox, oy, oz, highId);
        if (world.getBlockId(ox, oy, oz) != highId) {
            releaseStandIns(standIns);
            restoreRoundTripBlock(world, ox, oy, oz, before);
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr,
                    "Test.high_block_round_trip: the game did not put the test block at "
                            + ox
                            + ", "
                            + oy
                            + ", "
                            + oz
                            + ".");
            return -1L;
        }

        Chunk loaded;
        try {
            loaded = saveAndLoad(world, ox, oz);
        } catch (IOException e) {
            releaseStandIns(standIns);
            restoreRoundTripBlock(world, ox, oy, oz, before);
            Grug.hostFunctionErrorHappened(Grug.statePtr, "Test.high_block_round_trip: " + e);
            return -1L;
        }
        if (loaded == null) {
            releaseStandIns(standIns);
            restoreRoundTripBlock(world, ox, oy, oz, before);
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr,
                    "Test.high_block_round_trip: the chunk's save and load came back with no"
                            + " chunk.");
            return -1L;
        }

        int readBack = readBackId(loaded, ox, oy, oz);
        if (readBack != highId) {
            releaseStandIns(standIns);
            restoreRoundTripBlock(world, ox, oy, oz, before);
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr,
                    "Test.high_block_round_trip: block id "
                            + highId
                            + " came back "
                            + readBack
                            + " after the chunk's save and load; an id above "
                            + GrugBlocks.LAST_BLOCK_ID
                            + " did not survive the save's 12-bit form.");
            return -1L;
        }

        // The cell and the tables the way the test found them; the block stays registered, the way
        // a block of the pack's would be, with its item at the same slot.
        restoreRoundTripBlock(world, ox, oy, oz, before);
        releaseStandIns(standIns);
        return highId;
    }

    /** The block position the test origin names, floored the way every block position is. */
    private BlockPos originBlock(Vec3 origin) {
        return new BlockPos(
                (int) Math.floor(origin.x()),
                (int) Math.floor(origin.y()),
                (int) Math.floor(origin.z()));
    }

    /**
     * Takes every low id the pack's other mods left free, as stand-in items.
     *
     * <p>The stand-ins are items only, because a block's id and its item's sit in the same slot of
     * the two tables the search looks at, and an item is the smaller of the two to set aside.
     */
    private static List<Integer> takeStandIns() {
        List<Integer> standIns = new ArrayList<>();
        for (int id = GrugBlocks.START_BLOCK_ID; id <= GrugBlocks.LAST_BLOCK_ID; id++) {
            standInFor(id, standIns);
        }
        return standIns;
    }

    private static void standInFor(int id, List<Integer> standIns) {
        if (standInNeeded(id)) {
            addStandIn(id, standIns);
        }
    }

    @GrugGenerated("stand-ins: a low id that already holds an item is not one a run leaves free")
    private static boolean standInNeeded(int id) {
        return Block.blocksList[id] == null && Item.itemsList[id] == null;
    }

    /** Sets an item aside at a free low id, so the pool cannot hand that id out. */
    private static void addStandIn(int id, List<Integer> standIns) {
        Item.itemsList[id] = new GrugItem(id - 256, Grug.INVALID_GRUG_FILE_ID);
        standIns.add(id);
    }

    private static void registerRoundTripBlock(int highId) {
        Block block = new HighBlockRoundTripBlock(highId);
        block.setBlockName(HIGH_BLOCK_ID);
        ModLoader.registerBlock(block);
    }

    /** Puts the test block down and answers the id the cell held before it. */
    private static int placeRoundTripBlock(World world, int x, int y, int z, int highId) {
        int before = world.getBlockId(x, y, z);
        world.setBlockWithNotify(x, y, z, highId);
        return before;
    }

    private static void restoreRoundTripBlock(World world, int x, int y, int z, int before) {
        world.setBlockWithNotify(x, y, z, before);
    }

    private static Chunk saveAndLoad(World world, int ox, int oz) throws IOException {
        Chunk chunk = world.getChunkFromBlockCoords(ox, oz);
        IChunkLoader loader = world.getSaveHandler().getChunkLoader(world.worldProvider);
        loader.saveChunk(world, chunk);
        return loader.loadChunk(world, ox >> 4, oz >> 4);
    }

    private static int readBackId(Chunk loaded, int ox, int oy, int oz) {
        // The id the loaded chunk's own storage holds, not the one the world's still does: that is
        // the id the save format carried.
        return loaded.getBlockID(ox & 15, oy, oz & 15);
    }

    /** Puts back the ids the stand-in items took, which the pack's other mods had left free. */
    private static void releaseStandIns(List<Integer> standIns) {
        for (Integer id : standIns) {
            Item.itemsList[id] = null;
        }
        standIns.clear();
    }

    private final GrugBlockEntityNbtAccess blockEntityNbt = new GrugBlockEntityNbtAccess();

    @Override
    public GrugBlockEntityNbt.Access blockEntityNbt() {
        return blockEntityNbt;
    }

    @Override
    public double maxGameSpeed() {
        return CLIENT_TICKS_PER_FRAME;
    }

    @Override
    public void tickBlockEntity(Object blockEntityObj) {
        ((GrugBlockEntity) blockEntityObj).updateEntity();
    }

    /**
     * Runs the world's own tick the given number of times, at the point of the call.
     *
     * <p>This version splits one game tick in two calls, in this order: {@code
     * World.updateEntities}, which ticks the entities and the tile entities the block entities of a
     * mod are, then {@code World.tick}, which ticks the weather, the spawner, the chunk unload, the
     * world time, the scheduled updates, the random block ticks and the villages. A test's tick
     * runs from FML's pre-world-tick hook, so the advance happens before the game's own world tick
     * for that tick and two test ticks stay one world tick further apart than the count. See #753
     * and #780.
     */
    @GrugGenerated(
            "which process holds a level: this version has one world, the client's own, so the"
                    + " check that it is a world has no other side a test could reach")
    @Override
    public int tickLevel(Object levelObj, int ticks) {
        if (!(levelObj instanceof World)) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr,
                    "Test.tick_level: Only a level has a tick this process can run, and this is not"
                            + " one.");
            return 0;
        }
        return advanceLevelTicks((World) levelObj, ticks);
    }

    /**
     * The advance itself: one world tick, in the order this version's own game tick runs it, which
     * is {@code Minecraft.runTick}'s two calls, answering how many ticks it ran.
     *
     * <p>No test can see the order inside one advance: the test's own tick observes the state after
     * every tick it asked for, and grug has no way to schedule a block update that comes due at a
     * chosen tick of the advance, so the order is checked against the client's own game tick that
     * this method follows, not by a test.
     */
    private static int advanceLevelTicks(World world, int ticks) {
        for (int tick = 0; tick < ticks; tick++) {
            world.updateEntities();
            // The client sets the spawn flags from its difficulty before every world tick, so the
            // advance does the same rather than leave a flag a difficulty change made stale.
            applySpawnFlags(world);
            world.tick();
        }
        return ticks;
    }

    /**
     * Sets the world's spawn flags from its difficulty, as the client does before every world tick.
     *
     * <p>A run keeps the difficulty its save has, so the other side of the flag cannot be reached.
     */
    private static void applySpawnFlags(World world) {
        world.setAllowedSpawnTypes(GrugWorldReady.spawnsAllowed(world.difficultySetting), true);
    }

    @Override
    public void redraw(Object blockEntityObj) {
        TileEntity be = (TileEntity) blockEntityObj;
        // The world tells its renderers that the block changed, which marks the chunk around it for
        // compiling again, the same as a block being placed there.
        be.worldObj.markBlockNeedsUpdate(be.xCoord, be.yCoord, be.zCoord);
    }

    @Override
    public BlockPos getBlockPosOfBlockEntity(Object blockEntityObj) {
        TileEntity be = (TileEntity) blockEntityObj;
        return new BlockPos(be.xCoord, be.yCoord, be.zCoord);
    }

    // --- Test / Screenshot Methods ---

    @Override
    public boolean isWorldReady(Object playerObj) {
        EntityPlayer player = (EntityPlayer) playerObj;
        Object level = getLevel();

        // posY is the eye reference in this version, so the feet come from the collision box.
        return GrugWorldReady.isReady(
                (x, y, z) -> isAir(level, x, y, z),
                player.posX,
                player.boundingBox.minY,
                player.posZ);
    }

    @Override
    public void forgetSettledFrames() {
        renderSettle.reset();
    }

    /**
     * The client's frames, and whether the last one had work in it. See {@link
     * #frameHadPendingWork}.
     */
    final GrugRenderSettle renderSettle = new GrugRenderSettle();

    /**
     * The client's copy of the blocks a test has placed, for the frame hook to sample. See #804.
     */
    final GrugBlockSync blockSync = new GrugBlockSync();

    /**
     * The squared distance {@code RenderGlobal.updateRenderers} compiles a chunk within, mirrored
     * from its own check. A chunk further out is only rebuilt once the camera moves closer.
     */
    static final float COMPILE_DISTANCE_SQUARED = 256.0F;

    @Override
    public boolean registersModRecipes() {
        // GrugRecipeParser reads the mods' data/<namespace>/recipes tree in modsLoaded and hands
        // each recipe to ModLoader.addRecipe or addShapelessRecipe.
        return true;
    }

    @Override
    public Vec3 testPlayerEye(Object playerObj) {
        EntityPlayer player = (EntityPlayer) playerObj;
        return new Vec3(player.posX, player.posY, player.posZ);
    }

    boolean testPlayerSnapped = false;
    private double savedX, savedY, savedZ;
    private float savedYaw, savedPitch;

    /** Saves where the player was and snaps them to their own block's centre. */
    void snapPlayer(EntityPlayer player) {
        savedX = player.posX;
        savedY = player.posY;
        savedZ = player.posZ;
        savedYaw = player.rotationYaw;
        savedPitch = player.rotationPitch;
        testPlayerSnapped = true;

        // Snap the player to the centre of their own block and level the view, so every loader
        // frames a fixture identically. The snap stays within the player's own block, so it is a
        // move within a chunk the client has already lit; a teleport into a chunk it has not lit
        // crashes this generation of the game. The runner restores the saved position when the
        // test ends.
        player.setPositionAndRotation(
                GrugTestPlayerSnap.blockCentre(savedX),
                savedY,
                GrugTestPlayerSnap.blockCentre(savedZ),
                0.0F,
                0.0F);
    }

    @GrugGenerated("screenshot/GL integration: failure paths a healthy run cannot enter")
    void restorePlayerIfPresent(EntityPlayer player) {
        if (player == null) return;
        restorePlayer(player);
    }

    /** Puts the saved position back on the player. */
    private void restorePlayer(EntityPlayer player) {
        player.setPositionAndRotation(savedX, savedY, savedZ, savedYaw, savedPitch);
    }

    /** The id of the block at a world position, floored the way every block position is. */
    static int blockIdAt(World world, double x, double y, double z) {
        return world.getBlockId((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
    }

    @GrugGenerated("block resolution: an id outside the tables cannot be forced")
    static Block blockAt(int blockId) {
        return (blockId >= 0 && blockId < Block.blocksList.length)
                ? Block.blocksList[blockId]
                : null;
    }

    @GrugGenerated("a shape the overlay left non-cubic cannot be forced: the overlay resets it")
    static boolean fullCube(Block block) {
        return block.minX == 0.0
                && block.minY == 0.0
                && block.minZ == 0.0
                && block.maxX == 1.0
                && block.maxY == 1.0
                && block.maxZ == 1.0;
    }

    @Override
    @GrugGenerated("test helper: it asks a game's own block for its collision boxes")
    @SuppressWarnings("rawtypes")
    public boolean blockCollisionIntersects(
            Object levelObj,
            double x,
            double y,
            double z,
            double minX,
            double minY,
            double minZ,
            double maxX,
            double maxY,
            double maxZ) {
        World world = (World) levelObj;
        int blockX = (int) Math.floor(x);
        int blockY = (int) Math.floor(y);
        int blockZ = (int) Math.floor(z);
        Block block = blockAt(world, blockX, blockY, blockZ);
        if (block == null) {
            return false;
        }

        AxisAlignedBB area =
                AxisAlignedBB.getBoundingBox(
                        blockX + minX,
                        blockY + minY,
                        blockZ + minZ,
                        blockX + maxX,
                        blockY + maxY,
                        blockZ + maxZ);
        ArrayList boxes = new ArrayList();
        block.getCollidingBoundingBoxes(world, blockX, blockY, blockZ, area, boxes);
        return !boxes.isEmpty();
    }

    @Override
    @GrugGenerated("test helper: it asks a game's own block for its selection box")
    public boolean blockSelectionContains(
            Object levelObj,
            double x,
            double y,
            double z,
            double pointX,
            double pointY,
            double pointZ) {
        // A server's Block has no selection box: it belongs to the client's outline.
        if (refuseWithoutAClient("Test.block_selection")) return false;
        World world = (World) levelObj;
        int blockX = (int) Math.floor(x);
        int blockY = (int) Math.floor(y);
        int blockZ = (int) Math.floor(z);
        Block block = blockAt(world, blockX, blockY, blockZ);
        if (block == null) {
            return false;
        }

        // The game itself asks this after the block's state has set its bounds, which is what
        // RenderGlobal relies on before it draws the outline.
        block.setBlockBoundsBasedOnState(world, blockX, blockY, blockZ);
        AxisAlignedBB box = block.getSelectedBoundingBoxFromPool(world, blockX, blockY, blockZ);
        if (box == null) {
            return false;
        }

        double px = blockX + pointX;
        double py = blockY + pointY;
        double pz = blockZ + pointZ;
        return px >= box.minX
                && px <= box.maxX
                && py >= box.minY
                && py <= box.maxY
                && pz >= box.minZ
                && pz <= box.maxZ;
    }

    @Override
    public void addRedstoneDust(Object levelObj, double x, double y, double z) {
        // The three numbers after the position are the particle's colour, so zero gives the game's
        // red.
        ((World) levelObj).spawnParticle("reddust", x, y, z, 0, 0, 0);
    }

    @Override
    @GrugGenerated("test helper: it asks a game's own block for its display tick")
    public void displayTickForTest(Object levelObj, double x, double y, double z, long seed) {
        World world = (World) levelObj;
        int blockX = (int) Math.floor(x);
        int blockY = (int) Math.floor(y);
        int blockZ = (int) Math.floor(z);
        Block block = blockAt(world, blockX, blockY, blockZ);
        if (block != null) {
            if (getSide() != GrugSide.CLIENT && block instanceof GrugBlock) {
                // A dedicated server's Block has no randomDisplayTick (the client calls it), so
                // run the script's display tick directly with the same stream. The particles are
                // recorded in core and the server draws none (#1040).
                GrugDisplayTicks.run(
                        ((GrugBlock) block).blockFileId,
                        world,
                        blockX,
                        blockY,
                        blockZ,
                        new java.util.Random(seed)::nextFloat);
            } else {
                block.randomDisplayTick(world, blockX, blockY, blockZ, new java.util.Random(seed));
            }
        }
    }

    @Override
    @GrugGenerated("test helper: it asks a game's own block for its ray trace")
    public boolean blockRayTraceHits(
            Object levelObj,
            double x,
            double y,
            double z,
            double startX,
            double startY,
            double startZ,
            double endX,
            double endY,
            double endZ) {
        World world = (World) levelObj;
        int blockX = (int) Math.floor(x);
        int blockY = (int) Math.floor(y);
        int blockZ = (int) Math.floor(z);
        Block block = blockAt(world, blockX, blockY, blockZ);
        if (block == null) {
            return false;
        }

        // The same call this version's World.rayTraceBlocks makes for the first block its ray
        // reaches, so a click and this answer clip against the same box.
        MovingObjectPosition hit =
                block.collisionRayTrace(
                        world,
                        blockX,
                        blockY,
                        blockZ,
                        Vec3D.createVector(blockX + startX, blockY + startY, blockZ + startZ),
                        Vec3D.createVector(blockX + endX, blockY + endY, blockZ + endZ));
        return hit != null && hit.blockX == blockX && hit.blockY == blockY && hit.blockZ == blockZ;
    }

    /**
     * The block at a position, or null for air or an id nothing was registered for.
     *
     * <p>The only callers are the three test helpers above, and an id past the table cannot come
     * from a world, so the gate does not measure this lookup.
     */
    @GrugGenerated("test helper: the block lookup the three test helpers above share")
    static Block blockAt(World world, int x, int y, int z) {
        int blockId = world.getBlockId(x, y, z);
        if (blockId <= 0 || blockId >= Block.blocksList.length) {
            return null;
        }
        return Block.blocksList[blockId];
    }
}
