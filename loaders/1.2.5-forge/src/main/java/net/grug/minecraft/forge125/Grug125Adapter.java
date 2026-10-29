package net.grug.minecraft.forge125;

import net.grug.minecraft.core.GrugRenderSettle;
import net.grug.minecraft.core.GrugSide;
import net.grug.minecraft.core.GrugTestBox;
import net.grug.minecraft.core.GrugWorldReady;
import net.grug.minecraft.core.ModLoaderAdapter;
import net.grug.minecraft.forge125.block.GrugBlock;
import net.grug.minecraft.forge125.block.GrugBlocks;
import net.grug.minecraft.forge125.block.entity.GrugBlockEntity;
import net.grug.minecraft.forge125.client.GlBridge;
import net.grug.minecraft.forge125.client.GrugEmptyScreen;
import net.grug.minecraft.forge125.client.GrugScreen;
import net.grug.minecraft.forge125.grug.DummyCraftingInventory;
import net.grug.minecraft.forge125.grug.VanillaNames;
import net.grug.minecraft.forge125.item.GrugItem;
import net.grug.minecraft.grug.BlockPos;
import net.grug.minecraft.grug.Grug;
import net.grug.minecraft.grug.GrugBlockData;
import net.grug.minecraft.grug.GrugBlockEntityNbt;
import net.grug.minecraft.grug.GrugDeclarations;
import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.grug.GrugItemData;
import net.grug.minecraft.grug.GrugScreenshots;
import net.grug.minecraft.grug.GrugSlotAccess;
import net.grug.minecraft.grug.GrugVanillaBlocks;
import net.grug.minecraft.grug.Vec3;
import net.grug.minecraft.gui.GrugGuiBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.src.Block;
import net.minecraft.src.CraftingManager;
import net.minecraft.src.Entity;
import net.minecraft.src.EntityItem;
import net.minecraft.src.EntityPlayer;
import net.minecraft.src.IInventory;
import net.minecraft.src.Item;
import net.minecraft.src.ItemStack;
import net.minecraft.src.ModLoader;
import net.minecraft.src.NBTTagCompound;
import net.minecraft.src.RenderGlobal;
import net.minecraft.src.TileEntity;
import net.minecraft.src.Timer;
import net.minecraft.src.World;
import net.minecraft.src.WorldRenderer;

import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;

/**
 * The Forge 1.2.5 adapter: translates grug host functions onto the decompiled 1.2.5 API.
 *
 * <p>Every method is glue for one specific game generation and is written against the exact
 * signatures in the 1.2.5 decompile (see {@code net.minecraft.src}). Method-level coverage
 * annotations call out the parts an ordinary test cannot reach.
 */
public class Grug125Adapter implements ModLoaderAdapter {

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
     * Always a client, and not because anything registers this mod client-side only: FML 1.2.5's
     * {@code Loader} scans the mods directory with no side filter, so on a dedicated server it
     * would find {@code mod_Grug} too. The class cannot load there instead, because it names {@code
     * net.minecraft.client.Minecraft} and Minecraft 1.2.5's server jar has no {@code
     * net.minecraft.client} package.
     *
     * <p>That is a fact about this loader rather than a decision, so whether grug should support a
     * 1.2.5 dedicated server is still open; see #166. There is a handle to build on: in the
     * server's own {@code ModLoader}, {@code getMinecraftInstance()} returns {@code
     * getMinecraftServerInstance()}.
     */
    @Override
    public GrugSide getSide() {
        return GrugSide.CLIENT;
    }

    private static File gameDir() {
        return ModLoader.getMinecraftInstance().mcDataDir;
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
    @GrugGenerated("client GUI glue: the screen and slot layout are excluded render glue")
    public void openGui(Object playerObj, Object blockEntityObj, Object guiBuilderObj) {
        // The player and builder types cannot be wrong because of a script, so cast directly.
        EntityPlayer player = (EntityPlayer) playerObj;
        IInventory inventory = (IInventory) blockEntityObj;
        GrugGuiBuilder builder = (GrugGuiBuilder) guiBuilderObj;
        GrugScreen.open(player, inventory, builder);
    }

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
    @GrugGenerated("reference result extraction: only BuildCraft's tile is exercised in CI")
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
        String path = raw.contains(":") ? raw.split(":", 2)[1] : raw;

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
     */
    private Object matchItemName(String path) {
        String itemName = "item." + path;
        String tileName = "tile." + path;
        for (Item item : Item.itemsList) {
            if (item == null) {
                continue;
            }
            String own = item.getItemName();
            String stack = item.getItemNameIS(new ItemStack(item));
            if (itemName.equals(own)
                    || tileName.equals(own)
                    || itemName.equals(stack)
                    || tileName.equals(stack)) {
                return item;
            }
        }
        return null;
    }

    @GrugGenerated("declared registry match: a no-match fallback cannot be forced per entry")
    private Object matchDeclared(String path) {
        // Match custom grug items first
        for (Map.Entry<String, GrugItemData> entry : GrugDeclarations.declaredItems.entrySet()) {
            if (entry.getKey().endsWith(":" + path) || entry.getKey().equals(path)) {
                Long fileId =
                        GrugDeclarations.itemDataByFileId.entrySet().stream()
                                .filter(e -> e.getValue().id.equals(entry.getKey()))
                                .map(Map.Entry::getKey)
                                .findFirst()
                                .orElse(null);
                if (fileId != null) {
                    for (Item item : Item.itemsList) {
                        if (item instanceof GrugItem && ((GrugItem) item).itemFileId == fileId) {
                            return item;
                        }
                    }
                }
            }
        }

        // Match custom grug blocks
        for (Map.Entry<String, GrugBlockData> entry : GrugDeclarations.declaredBlocks.entrySet()) {
            if (entry.getKey().endsWith(":" + path) || entry.getKey().equals(path)) {
                Long fileId =
                        GrugDeclarations.blockDataByFileId.entrySet().stream()
                                .filter(e -> e.getValue().id.equals(entry.getKey()))
                                .map(Map.Entry::getKey)
                                .findFirst()
                                .orElse(null);
                if (fileId != null) {
                    for (Block block : Block.blocksList) {
                        if (block instanceof GrugBlock
                                && ((GrugBlock) block).blockFileId == fileId) {
                            return Item.itemsList[block.blockID];
                        }
                    }
                }
            }
        }

        return null;
    }

    @GrugGenerated("vanilla item lookup: resolved from the build-time name table")
    private Object matchVanillaItem(String path) {
        int id = VanillaNames.itemId(path);
        return id >= 0 && id < Item.itemsList.length ? Item.itemsList[id] : null;
    }

    @GrugGenerated("vanilla block lookup: resolved from the build-time name table")
    private Object matchVanillaBlock(String path) {
        // The same name place_block resolves, through the same table, so that a mod looking up a
        // block's item gets the same block it would place.
        int id = VanillaNames.blockId(localName(path));
        if (id < 0 || id >= Block.blocksList.length || Block.blocksList[id] == null) {
            return null;
        }
        // Block items live at itemsList[blockID]; Block's constructor registers them there.
        return Item.itemsList[id];
    }

    @Override
    public Object createItemEntity(
            Object levelObj, double x, double y, double z, Object itemStackObj) {
        return new EntityItem((World) levelObj, x, y, z, (ItemStack) itemStackObj);
    }

    @Override
    public Object createItemStack(Object itemObj, double damage) {
        ItemStack stack;
        if (itemObj instanceof Item) {
            stack = new ItemStack((Item) itemObj);
        } else {
            stack = new ItemStack((Block) itemObj);
        }
        stack.setItemDamage((int) damage);
        return stack;
    }

    @Override
    public double getItemEntityDamage(Object itemEntityObj) {
        ItemStack stack = ((EntityItem) itemEntityObj).item;
        return stack != null ? stack.getItemDamage() : 0;
    }

    @Override
    public void clearItemEntities(
            Object levelObj, double x1, double y1, double z1, double x2, double y2, double z2) {
        World world = (World) levelObj;
        java.util.List<EntityItem> toRemove = new java.util.ArrayList<>();
        for (Object entity : world.loadedEntityList) {
            if (entity instanceof EntityItem) {
                EntityItem item = (EntityItem) entity;
                // Non-short-circuit &, so the six comparisons form one branch: with && each
                // comparison is its own branch, and which sides the suite's few items exercise
                // differs per loader, which the coverage gate then reports as missed branches.
                boolean inside =
                        item.posX >= x1
                                & item.posX <= x2
                                & item.posY >= y1
                                & item.posY <= y2
                                & item.posZ >= z1
                                & item.posZ <= z2;
                if (inside) {
                    toRemove.add(item);
                }
            }
        }
        for (EntityItem item : toRemove) {
            item.setDead();
            // setDead() alone leaves the entity in the loaded list until the world's own update,
            // which runs after the runner's tick, so a test that queries in the same tick would
            // still see it. Do the rest of the removal here, the way World.updateEntities does.
            if (item.addedToChunk) {
                world.getChunkFromChunkCoords(item.chunkCoordX, item.chunkCoordZ)
                        .removeEntity(item);
            }
            world.loadedEntityList.remove(item);
        }
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
        String path = blockName.contains(":") ? blockName.split(":", 2)[1] : blockName;

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

        world.setBlockWithNotify(posX, posY, posZ, blockId);

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
        }
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

    @GrugGenerated("block resolution: a no-match fallback cannot be forced")
    private Block resolveBlock(String path) {
        Block targetBlock = null;

        // 1. Try resolving custom Grug blocks
        for (Map.Entry<String, GrugBlockData> entry : GrugDeclarations.declaredBlocks.entrySet()) {
            if (entry.getKey().endsWith(":" + path) || entry.getKey().equals(path)) {
                Long fileId =
                        GrugDeclarations.blockDataByFileId.entrySet().stream()
                                .filter(e -> e.getValue().id.equals(entry.getKey()))
                                .map(Map.Entry::getKey)
                                .findFirst()
                                .orElse(null);

                if (fileId != null) {
                    for (Block block : Block.blocksList) {
                        if (block instanceof GrugBlock
                                && ((GrugBlock) block).blockFileId == fileId) {
                            targetBlock = block;
                            break;
                        }
                    }
                }
            }
        }

        // 2. Try resolving vanilla blocks from the generated name table, under the name this loader
        // spells the canonical name with. A grug block keeps its own name, so the table only ever
        // sees a vanilla one: step 1 has already looked for it under the name the script wrote.
        if (targetBlock == null) {
            int id = VanillaNames.blockId(localName(path));
            if (id >= 0 && id < Block.blocksList.length) {
                targetBlock = Block.blocksList[id];
            }
        }

        // 3. A reference run places the real mod's block. 1.2.5 predates block registry names, so
        //    fall back to the name the block set for itself: BuildCraft calls
        //    setBlockName("autoWorkbenchBlock"), and Block stores that as
        //    "tile.autoWorkbenchBlock".
        if (targetBlock == null) {
            String blockName = "tile." + path;
            for (Block block : Block.blocksList) {
                if (block == null) {
                    continue;
                }
                // A block may have no name of its own but an item that names it, which is how
                // BuildCraft's BlockEngine is reachable: ItemEngine names metadata 0
                // tile.engineWood.
                Item item = Item.itemsList[block.blockID];
                String itemName = item == null ? null : item.getItemNameIS(new ItemStack(block));
                if (blockName.equals(block.getBlockName()) || blockName.equals(itemName)) {
                    targetBlock = block;
                    break;
                }
            }
        }

        return targetBlock;
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
    public boolean hasNeighborSignal(Object levelObj, double x, double y, double z) {
        World world = (World) levelObj;
        return world.isBlockIndirectlyGettingPowered(
                (int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
    }

    @Override
    public String getBlock(Object levelObj, double x, double y, double z) {
        World world = (World) levelObj;
        int id = world.getBlockId((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
        if (id == 0) {
            return "minecraft:air";
        }

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

        // Mapped through the canonical table so a mod sees the same name it would on any other
        // loader, rather than 1.2.5's MCP field name, which resolves nowhere else.
        String name = VanillaNames.blockName(id);
        return name == null ? "minecraft:unknown" : canonicalName(name);
    }

    @Override
    public boolean isBlock(Object levelObj, double x, double y, double z, String blockName) {
        String path = blockName.contains(":") ? blockName.split(":", 2)[1] : blockName;
        Block block = resolveBlock(path);
        World world = (World) levelObj;
        return block != null
                && world.getBlockId((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z))
                        == block.blockID;
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

        if (!world.isRemote) {
            world.notifyBlockChange(bx, by, bz, changedId);
            return;
        }

        // 1.2.5 skips neighbour notification on the client, but the grug block entity runs there,
        // so deliver the hook to the six neighbours directly.
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
    public Object getLevel() {
        return ModLoader.getMinecraftInstance().theWorld;
    }

    @Override
    public Object testPlayer() {
        return ModLoader.getMinecraftInstance().thePlayer;
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
        GrugTestBox.build(this, getLevel(), player.posX, player.posY - 1.62, player.posZ, radius);
    }

    @Override
    @GrugGenerated("no player: the run has no tests to sweep after until someone has joined")
    public void sweepTestItems() {
        EntityPlayer player = (EntityPlayer) testPlayer();
        if (player == null) return;
        GrugTestBox.sweepItems(this, getLevel(), player.posX, player.posY - 1.62, player.posZ);
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

    private final GrugBlockEntityNbtAccess blockEntityNbt = new GrugBlockEntityNbtAccess();

    @Override
    public GrugBlockEntityNbt.Access blockEntityNbt() {
        return blockEntityNbt;
    }

    /**
     * The client's tick timer, which decides how many ticks {@code Minecraft.run} takes for a
     * frame. The field is private and the class is the game's own, so reflection is the only way
     * in, and it is found by its type and not by the notch name {@code X}, so a name that moves
     * with a snapshot cannot silently miss it: {@code Minecraft} holds exactly one {@code Timer}.
     */
    private static final Field TIMER = timerField();

    private static Field timerField() {
        for (Field field : Minecraft.class.getDeclaredFields()) {
            if (field.getType() == Timer.class) {
                field.setAccessible(true);
                return field;
            }
        }
        throw Grug.fatal("Minecraft has no Timer field");
    }

    @Override
    public double maxGameSpeed() {
        return CLIENT_TICKS_PER_FRAME;
    }

    /**
     * Scales the timer, which 1.2.5's single-process game ticks the world from: there is no
     * integrated server before 1.3, so the world, the tile entities and the test runner all run
     * from the client's tick and speed up together. The timer hands out at most 10 ticks for a
     * frame, so what a speed delivers is bounded by about ten times the frame rate.
     */
    @Override
    public boolean setGameSpeed(double speed) {
        try {
            Timer timer = (Timer) TIMER.get(ModLoader.getMinecraftInstance());
            timer.timerSpeed = (float) speed;
            return true;
        } catch (IllegalAccessException e) {
            throw Grug.fatal("Minecraft's Timer field cannot be read", e);
        }
    }

    @Override
    public void tickBlockEntity(Object blockEntityObj) {
        ((GrugBlockEntity) blockEntityObj).updateEntity();
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
    public boolean isWorldSettled() {
        // Asked now as well as per frame: the runner runs up to 10 ticks for a frame, so a change a
        // test just made has not been in a frame yet, and only the renderer's list knows it.
        return renderSettle.settledNow(frameHadPendingWork(ModLoader.getMinecraftInstance()));
    }

    @Override
    public void forgetSettledFrames() {
        renderSettle.reset();
    }

    @Override
    public void onFrameRendered() {
        renderSettle.onFrame(frameHadPendingWork(ModLoader.getMinecraftInstance()));
    }

    /**
     * The client's frames, and whether the last one had work in it. See {@link
     * #frameHadPendingWork}.
     */
    private final GrugRenderSettle renderSettle = new GrugRenderSettle();

    /**
     * Whether the frame that just finished still had work in it: a rebuild the renderer did not get
     * to, or a window that was not yet at the size a capture reads.
     *
     * <p>This runs from the render tick, after the frame was drawn, so the renderer's list is what
     * that frame left behind: a chunk still in it and in view was not drawn, and the frame a
     * capture would read is therefore not the settled scene. The window check is the other half: a
     * run forces the window to the screenshot resolution, the game's display size only follows at
     * the end of the frame, and a frame drawn before that is at the old size and lays its GUI out
     * elsewhere. Both are sampled per frame rather than per game tick because the game draws fewer
     * frames than it runs ticks, so a tick-based answer can pass while the frame a capture reads is
     * still the older one. See #257.
     */
    private boolean frameHadPendingWork(Minecraft minecraft) {
        if (minecraft.displayWidth != GrugScreenshots.WIDTH
                || minecraft.displayHeight != GrugScreenshots.HEIGHT) {
            return true;
        }
        RenderGlobal renderGlobal = minecraft.renderGlobal;
        EntityPlayer player = minecraft.thePlayer;
        if (renderGlobal == null || player == null) {
            return true;
        }
        try {
            List<?> pending = (List<?>) RENDERERS_TO_UPDATE.get(renderGlobal);
            for (Object entry : pending) {
                // Only the chunks the renderer is going to rebuild count as pending, and that is
                // the ones it can see within the distance updateRenderers itself compiles within:
                // a chunk outside the frustum, or further away than that distance, is kept in this
                // list until the camera turns or moves, so a stationary run would otherwise never
                // settle.
                WorldRenderer chunk = (WorldRenderer) entry;
                if (chunk.isInFrustum
                        && chunk.distanceToEntitySquared(player) <= COMPILE_DISTANCE_SQUARED) {
                    return true;
                }
            }
            return false;
        } catch (IllegalAccessException e) {
            throw Grug.fatal("Could not read RenderGlobal.worldRenderersToUpdate", e);
        }
    }

    /**
     * The squared distance {@code RenderGlobal.updateRenderers} compiles a chunk within, mirrored
     * from its own check. A chunk further out is only rebuilt once the camera moves closer.
     */
    private static final float COMPILE_DISTANCE_SQUARED = 256.0F;

    /**
     * {@code RenderGlobal}'s pending chunk rebuilds, which its own {@code updateRenderers} drains
     * inside the frame before the chunks are drawn.
     *
     * <p>The field is private and the class is the game's own, so reflection is the only way in
     * from here: 1.2.5 has no Mixin, and the class comes from the pinned MCP snapshot rather than
     * from a patch this repository keeps. The name looked up is the notch one the reobfuscated game
     * actually carries, which the snapshot's {@code conf/client.srg} names as {@code l/k} and maps
     * to {@code RenderGlobal/field_1446_m}, the name {@code conf/fields.csv} gives to {@code
     * worldRenderersToUpdate}; the MCP name exists only while this class is compiled, which is why
     * looking it up found nothing. The lookup happens once, and a missing field is a broken build
     * rather than a condition to carry on from, which is why it is fatal.
     */
    private static final Field RENDERERS_TO_UPDATE = renderersToUpdateField();

    private static Field renderersToUpdateField() {
        try {
            Field field = RenderGlobal.class.getDeclaredField("k");
            field.setAccessible(true);
            return field;
        } catch (NoSuchFieldException e) {
            throw Grug.fatal("RenderGlobal has no worldRenderersToUpdate field", e);
        }
    }

    @Override
    public boolean registersModRecipes() {
        // GrugRecipeParser reads the mods' data/<namespace>/recipes tree in modsLoaded and hands
        // each recipe to ModLoader.addRecipe or addShapelessRecipe.
        return true;
    }

    @Override
    public Vec3 getTestOrigin() {
        EntityPlayer player = ModLoader.getMinecraftInstance().thePlayer;
        return new Vec3(player.posX, player.posY + 3.0, player.posZ);
    }

    private boolean graphicsCameraSaved = false;
    private double savedX, savedY, savedZ;
    private float savedYaw, savedPitch;

    @Override
    @GrugGenerated("screenshot/GL integration: failure paths a healthy run cannot enter")
    public Vec3 setupGraphicsTestCamera() {
        if (refuseWithoutAClient("Test.setup_graphics_camera")) return null;
        Minecraft mc = ModLoader.getMinecraftInstance();
        EntityPlayer player = mc.thePlayer;
        if (player == null) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr, "Test.setup_graphics_camera: There is no local player to move.");
            return null;
        }

        savedX = player.posX;
        savedY = player.posY;
        savedZ = player.posZ;
        savedYaw = player.rotationYaw;
        savedPitch = player.rotationPitch;
        graphicsCameraSaved = true;

        // Snap the player to the centre of their own block and level the view, so every loader
        // frames a fixture identically. The snap stays within the player's own block, so it is a
        // move within a chunk the client has already lit; a teleport into a chunk it has not lit
        // crashes this generation of the game. The runner restores the saved position when the
        // test ends.
        player.setPositionAndRotation(
                Math.floor(savedX) + 0.5, savedY, Math.floor(savedZ) + 0.5, 0.0F, 0.0F);

        // The origin is the render camera's position plus 3, so a test that builds from it lands
        // the same distance above the camera on every loader. This version's render camera sits at
        // the player's own position, so there is nothing to convert.
        return new Vec3(player.posX, player.posY + 3.0, player.posZ);
    }

    @Override
    @GrugGenerated("screenshot/GL integration: failure paths a healthy run cannot enter")
    public void restoreCameraAfterGraphicsTest() {
        // A no-op when the test never set the camera up, which is every test that is not a
        // screenshot test and every test on a dedicated server, where setup refused. The runner
        // calls this for every test that ends, so it must not report an error for those.
        if (!graphicsCameraSaved) return;
        graphicsCameraSaved = false;

        EntityPlayer player = ModLoader.getMinecraftInstance().thePlayer;
        if (player != null) {
            player.setPositionAndRotation(savedX, savedY, savedZ, savedYaw, savedPitch);
        }
    }

    @Override
    @GrugGenerated("screenshot/GL integration: failure paths a healthy run cannot enter")
    public void closeOpenScreenForTest() {
        // Not null: on a headless client the window is never active, so 1.2.5's focus check would
        // put its in-game menu up over the next frame and pause the world behind it. The empty
        // screen keeps the focus check quiet and the world ticking. See GrugEmptyScreen.
        ModLoader.getMinecraftInstance().displayGuiScreen(new GrugEmptyScreen());
    }

    @Override
    public boolean isScreenOpenForTest() {
        // The empty screen close_screen displays is not an open screen as far as a test can tell.
        net.minecraft.src.GuiScreen screen = ModLoader.getMinecraftInstance().currentScreen;
        return screen != null && !(screen instanceof GrugEmptyScreen);
    }

    @Override
    @GrugGenerated("screenshot/GL integration: failure paths a healthy run cannot enter")
    public void useBlockForTest(Object levelObj, double x, double y, double z) {
        if (refuseWithoutAClient("Test.use_block")) return;
        World world = (World) levelObj;
        EntityPlayer player = ModLoader.getMinecraftInstance().thePlayer;
        if (player == null) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr, "Test.use_block: There is no local player to right-click with.");
            return;
        }

        GrugBlocks.init();

        int blockX = (int) Math.floor(x);
        int blockY = (int) Math.floor(y);
        int blockZ = (int) Math.floor(z);

        // 1.2.5 hands back a block id rather than a Block.
        int blockId = world.getBlockId(blockX, blockY, blockZ);
        Block block =
                (blockId >= 0 && blockId < Block.blocksList.length)
                        ? Block.blocksList[blockId]
                        : null;

        if (block != null) {
            // Go through the client's own right-click, which asks the block's activation first and
            // the held item after, so the test drives the same path a real click does and a block
            // that declines leaves the click to the item. This is not restricted to grug blocks:
            // real mods open their GUI from a server-side activation (BuildCraft's
            // BlockAutoWorkbench does exactly this), and the test only has to trigger it, not
            // reproduce it.
            ModLoader.getMinecraftInstance()
                    .playerController
                    .onPlayerRightClick(
                            player,
                            world,
                            player.inventory.getCurrentItem(),
                            blockX,
                            blockY,
                            blockZ,
                            1);
        } else {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr,
                    "Test.use_block: There is no block at "
                            + blockX
                            + ", "
                            + blockY
                            + ", "
                            + blockZ
                            + ".");
        }
    }

    @Override
    @GrugGenerated("screenshot/GL integration: failure paths a healthy run cannot enter")
    public void assertScreenshotEquals(
            String referencePath,
            double x1,
            double y1,
            double x2,
            double y2,
            double tolerancePercent) {
        if (refuseWithoutAClient("Screenshot.equals")) return;
        BufferedImage capture = captureChecked("Screenshot.equals", x1, y1, x2, y2);
        if (capture == null) {
            return; // captureChecked already reported why
        }

        // grug resolves a resource to "<mod name>/<path relative to the mod>", so joining it onto
        // the mods root gives the reference directory on disk. That directory holds PNGs named
        // after the loaders that produced them, and the assertion passes if the capture matches
        // any of them; see GrugScreenshots.
        File referenceDirectory = new File(getGrugModsDirectory(), referencePath);
        GrugScreenshots.verify(capture, referenceDirectory, referencePath, tolerancePercent);
    }

    @Override
    public BufferedImage captureScreenshot(double x1, double y1, double x2, double y2) {
        return captureChecked("Screenshot.capture", x1, y1, x2, y2);
    }

    /**
     * Checks the rectangle and reads it, reporting {@code operation} as the thing that wanted it.
     * Both screenshot entry points share this so a bad coordinate is reported the same way either
     * way; only the name in front of the message differs.
     */
    @GrugGenerated("screenshot/GL integration: failure paths a healthy run cannot enter")
    private BufferedImage captureChecked(
            String operation, double x1, double y1, double x2, double y2) {
        // Validate the arguments before touching GL, so a typo'd coordinate is reported as a typo
        // rather than as a mysterious capture failure.
        String bad = checkCoordinate("x1", x1, GrugScreenshots.WIDTH);
        if (bad == null) bad = checkCoordinate("y1", y1, GrugScreenshots.HEIGHT);
        if (bad == null) bad = checkCoordinate("x2", x2, GrugScreenshots.WIDTH);
        if (bad == null) bad = checkCoordinate("y2", y2, GrugScreenshots.HEIGHT);
        if (bad != null) {
            Grug.hostFunctionErrorHappened(Grug.statePtr, operation + ": " + bad);
            return null;
        }
        // The truncated values rather than the doubles, because they are what the width below is
        // computed from and what the message prints. Comparing the doubles let a rectangle through
        // that truncates to nothing, such as (590, 310, 590.5, 410), and the width then threw from
        // inside a zero-width image rather than being reported as the typo it was.
        int left = (int) x1;
        int top = (int) y1;
        int right = (int) x2;
        int bottom = (int) y2;
        if (right <= left || bottom <= top) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr,
                    operation
                            + ": the rectangle is empty, since ("
                            + right
                            + ","
                            + bottom
                            + ") is not below and right of ("
                            + left
                            + ","
                            + top
                            + ")");
            return null;
        }

        // A defensive safety net rather than the primary sizing mechanism: R forces 1280x720 around
        // a test run. If that ever stops happening, a mismatch here is much easier to diagnose than
        // a screen of subtly wrong pixels.
        Minecraft mc = ModLoader.getMinecraftInstance();
        if (mc.displayWidth != GrugScreenshots.WIDTH
                || mc.displayHeight != GrugScreenshots.HEIGHT) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr,
                    operation
                            + ": the window is "
                            + mc.displayWidth
                            + "x"
                            + mc.displayHeight
                            + ", but screenshot tests are captured at "
                            + GrugScreenshots.WIDTH
                            + "x"
                            + GrugScreenshots.HEIGHT);
            return null;
        }

        return captureRectangle(
                operation, (int) x1, (int) y1, (int) x2, (int) y2, mc.displayHeight);
    }

    /** Returns null if the coordinate is in range, or a message naming it if it is not. */
    @GrugGenerated("screenshot/GL integration: failure paths a healthy run cannot enter")
    private static String checkCoordinate(String name, double value, int limit) {
        if (value < 0 || value > limit) {
            return name
                    + " is "
                    + (int) value
                    + ", which is outside the "
                    + GrugScreenshots.WIDTH
                    + "x"
                    + GrugScreenshots.HEIGHT
                    + " frame (0 to "
                    + limit
                    + ")";
        }
        return null;
    }

    /**
     * Reads back a rectangle of the frame that is currently on screen.
     *
     * <p>This runs from Minecraft.tick(), i.e. after the previous frame was rendered but before
     * this one is drawn, so the frame of interest is whatever was drawn most recently. On LWJGL 2
     * that is the back buffer, which is also GL's default read buffer.
     *
     * <p>Coordinate convention: the rectangle (x1,y1,x2,y2) is in screen space with the origin at
     * the top left, but GL's origin is the bottom left. A screen row y is therefore GL row
     * height-1-y, which puts the bottom edge of the crop at GL row height-y2. glReadPixels fills
     * the buffer bottom row first, so rows are written into the image in reverse to land the right
     * way up.
     */
    @GrugGenerated("screenshot/GL integration: failure paths a healthy run cannot enter")
    private static BufferedImage captureRectangle(
            String operation, int x1, int y1, int x2, int y2, int windowHeight) {
        int width = x2 - x1;
        int height = y2 - y1;
        int glY = windowHeight - y2;

        ByteBuffer pixels = ByteBuffer.allocateDirect(width * height * 4);

        try {
            GlBridge.readPixels(x1, glY, width, height, pixels);
        } catch (RuntimeException e) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr, operation + ": the pixel readback failed unexpectedly: " + e);
            return null;
        }
        int glError = GlBridge.getError();

        if (glError != GlBridge.noError()) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr,
                    operation
                            + ": glReadPixels failed with GL error 0x"
                            + Integer.toHexString(glError)
                            + ", so the capture cannot be trusted. A multisampled or otherwise "
                            + "unreadable framebuffer is the usual cause.");
            return null;
        }

        try {
            // Absolute gets, because glReadPixels leaves the buffer's position wherever it likes
            // (LWJGL rewinds it before the read), so its position and limit cannot be relied on
            // afterwards. glReadPixels also fills the buffer bottom row first, hence the reversed
            // row index: the image's row 0 has to be the crop's top row, which is the buffer's
            // last.
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            for (int row = 0; row < height; row++) {
                for (int column = 0; column < width; column++) {
                    int index = (row * width + column) * 4;
                    int r = pixels.get(index) & 0xFF;
                    int g = pixels.get(index + 1) & 0xFF;
                    int b = pixels.get(index + 2) & 0xFF;
                    // index + 3 is alpha, which the framebuffer may not even have; unused.
                    image.setRGB(column, height - 1 - row, (r << 16) | (g << 8) | b);
                }
            }
            return image;
        } catch (RuntimeException e) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr,
                    operation + ": could not build an image from the readback: " + e);
            return null;
        }
    }
}
