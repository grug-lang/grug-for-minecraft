package net.grug.minecraft.forge125;

import net.grug.minecraft.core.GrugWorldReady;
import net.grug.minecraft.core.ModLoaderAdapter;
import net.grug.minecraft.forge125.block.GrugBlock;
import net.grug.minecraft.forge125.block.GrugBlocks;
import net.grug.minecraft.forge125.block.entity.GrugBlockEntity;
import net.grug.minecraft.forge125.client.GlBridge;
import net.grug.minecraft.forge125.client.GrugScreen;
import net.grug.minecraft.forge125.grug.DummyCraftingInventory;
import net.grug.minecraft.forge125.grug.VanillaNames;
import net.grug.minecraft.forge125.item.GrugItem;
import net.grug.minecraft.grug.BlockPos;
import net.grug.minecraft.grug.Grug;
import net.grug.minecraft.grug.GrugBlockData;
import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.grug.GrugItemData;
import net.grug.minecraft.grug.GrugScreenshots;
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
import net.minecraft.src.TileEntity;
import net.minecraft.src.World;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.ByteBuffer;
import java.util.Map;

/**
 * The Forge 1.2.5 adapter: translates grug host functions onto the decompiled 1.2.5 API.
 *
 * <p>Every method is glue for one specific game generation and is written against the exact
 * signatures in the 1.2.5 decompile (see {@code net.minecraft.src}). Method-level coverage
 * annotations call out the parts an ordinary test cannot reach.
 */
public class Grug125Adapter implements ModLoaderAdapter {

    private static File gameDir() {
        return ModLoader.getMinecraftInstance().mcDataDir;
    }

    @Override
    public File getGameDirectory() {
        return gameDir();
    }

    @Override
    public File getGrugModsDirectory() {
        return mod_Grug.getActiveGrugModsDir(new File(gameDir(), "grug_mods"));
    }

    @Override
    public void logInfo(String message) {
        mod_Grug.LOGGER.info(message);
    }

    @Override
    public void logError(String message) {
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
            Object blockEntityObj, Object itemObj, double damage, double amount) {
        IInventory inventory = (IInventory) blockEntityObj;
        Item item = (Item) itemObj;
        int meta = (int) damage;
        int maxStack = item.getItemStackLimit();
        int remaining = (int) amount;

        // Top up matching stacks first, then use empty slots, like a player's inventory fills.
        for (int i = 0; i < inventory.getSizeInventory() && remaining > 0; i++) {
            ItemStack stack = inventory.getStackInSlot(i);
            if (stack != null && stack.getItem() == item && stack.getItemDamage() == meta) {
                int room = maxStack - stack.stackSize;
                if (room > 0) {
                    int add = Math.min(room, remaining);
                    stack.stackSize += add;
                    remaining -= add;
                }
            }
        }

        for (int i = 0; i < inventory.getSizeInventory() && remaining > 0; i++) {
            if (inventory.getStackInSlot(i) == null) {
                int add = Math.min(maxStack, remaining);
                ItemStack stack = new ItemStack(item, add);
                stack.setItemDamage(meta);
                inventory.setInventorySlotContents(i, stack);
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

        return matchVanillaBlock(path);
    }

    @GrugGenerated("declared registry match: a no-match fallback cannot be forced per entry")
    private Object matchDeclared(String path) {
        // Match custom grug items first
        for (Map.Entry<String, GrugItemData> entry : Grug.declaredItems.entrySet()) {
            if (entry.getKey().endsWith(":" + path) || entry.getKey().equals(path)) {
                Long fileId =
                        Grug.itemDataByFileId.entrySet().stream()
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
        for (Map.Entry<String, GrugBlockData> entry : Grug.declaredBlocks.entrySet()) {
            if (entry.getKey().endsWith(":" + path) || entry.getKey().equals(path)) {
                Long fileId =
                        Grug.blockDataByFileId.entrySet().stream()
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
        int id = VanillaNames.blockId(path);
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
        Block targetBlock = resolveBlock(path);

        if (targetBlock != null) {
            int posX = (int) Math.floor(x);
            int posY = (int) Math.floor(y);
            int posZ = (int) Math.floor(z);

            world.setBlockWithNotify(posX, posY, posZ, targetBlock.blockID);

            // A placement that reports false because the block was already there is not a failure,
            // so the block's presence is the check rather than the return value. A y outside the
            // 256-block world height is refused, and a position in a chunk the client has not
            // received swallows the write too. See #151.
            if (world.getBlockId(posX, posY, posZ) != targetBlock.blockID) {
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
        } else {
            mod_Grug.LOGGER.severe("placeBlock failed: Could not resolve block " + blockName);
        }
    }

    @GrugGenerated("block resolution: a no-match fallback cannot be forced")
    private Block resolveBlock(String path) {
        Block targetBlock = null;

        // 1. Try resolving custom Grug blocks
        for (Map.Entry<String, GrugBlockData> entry : Grug.declaredBlocks.entrySet()) {
            if (entry.getKey().endsWith(":" + path) || entry.getKey().equals(path)) {
                Long fileId =
                        Grug.blockDataByFileId.entrySet().stream()
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

        // 2. Try resolving vanilla blocks from the generated name table
        if (targetBlock == null) {
            int id = VanillaNames.blockId(path);
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
                if (block != null && blockName.equals(block.getBlockName())) {
                    targetBlock = block;
                    break;
                }
            }
        }

        return targetBlock;
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
                GrugBlockData data = Grug.blockDataByFileId.get(((GrugBlock) block).blockFileId);
                if (data != null) {
                    return data.id;
                }
            }
        }

        String name = VanillaNames.blockName(id);
        return name == null ? "minecraft:unknown" : "minecraft:" + name;
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
    public Object getClientLevel() {
        return ModLoader.getMinecraftInstance().theWorld;
    }

    @Override
    public Object getPlayer() {
        return ModLoader.getMinecraftInstance().thePlayer;
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

    @Override
    public BlockPos getBlockPosOfBlockEntity(Object blockEntityObj) {
        TileEntity be = (TileEntity) blockEntityObj;
        return new BlockPos(be.xCoord, be.yCoord, be.zCoord);
    }

    // --- Test / Screenshot Methods ---

    @Override
    public boolean isWorldReady(Object playerObj) {
        EntityPlayer player = (EntityPlayer) playerObj;
        Object level = getClientLevel();

        // posY is the eye reference in this version, so the feet come from the collision box.
        return GrugWorldReady.isReady(
                (x, y, z) -> isAir(level, x, y, z),
                player.posX,
                player.boundingBox.minY,
                player.posZ);
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

        // Level the view so the frame does not depend on which way the player was looking, but
        // deliberately do NOT move them: teleporting into a chunk the client has not lit yet
        // crashes this generation of the game. A screenshot test crops a rectangle with the GUI
        // centred, so the terrain behind it never matters.
        player.setPositionAndRotation(player.posX, player.posY, player.posZ, 0.0F, 0.0F);

        return new Vec3(player.posX, player.posY + 3.0, player.posZ);
    }

    @Override
    @GrugGenerated("screenshot/GL integration: failure paths a healthy run cannot enter")
    public void restoreCameraAfterGraphicsTest() {
        if (!graphicsCameraSaved) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr,
                    "Test.restore_camera: No saved position; call Test.setup_graphics_camera"
                            + " first.");
            return;
        }
        graphicsCameraSaved = false;

        EntityPlayer player = ModLoader.getMinecraftInstance().thePlayer;
        if (player != null) {
            player.setPositionAndRotation(savedX, savedY, savedZ, savedYaw, savedPitch);
        }
    }

    @Override
    @GrugGenerated("screenshot/GL integration: failure paths a healthy run cannot enter")
    public void useBlockForTest(Object levelObj, double x, double y, double z) {
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
            // Go through the block's own activation hook so the test drives the same code path a
            // real right-click does, instead of a copy of the block's GUI layout. This is not
            // restricted to grug blocks: real mods open their GUI from a server-side activation
            // (BuildCraft's BlockAutoWorkbench does exactly this), and the test only has to trigger
            // it, not reproduce it.
            block.blockActivated(world, blockX, blockY, blockZ, player);
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
        // Validate the arguments before touching GL, so a typo'd coordinate is reported as a typo
        // rather than as a mysterious capture failure.
        String bad = checkCoordinate("x1", x1, GrugScreenshots.WIDTH);
        if (bad == null) bad = checkCoordinate("y1", y1, GrugScreenshots.HEIGHT);
        if (bad == null) bad = checkCoordinate("x2", x2, GrugScreenshots.WIDTH);
        if (bad == null) bad = checkCoordinate("y2", y2, GrugScreenshots.HEIGHT);
        if (bad != null) {
            Grug.hostFunctionErrorHappened(Grug.statePtr, "Screenshot.equals: " + bad);
            return;
        }
        if (x2 <= x1 || y2 <= y1) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr,
                    "Screenshot.equals: the rectangle is empty, since ("
                            + (int) x2
                            + ","
                            + (int) y2
                            + ") is not below and right of ("
                            + (int) x1
                            + ","
                            + (int) y1
                            + ")");
            return;
        }

        // A defensive safety net rather than the primary sizing mechanism: R forces 1280x720 around
        // a test run. If that ever stops happening, a mismatch here is much easier to diagnose than
        // a screen of subtly wrong pixels.
        Minecraft mc = ModLoader.getMinecraftInstance();
        if (mc.displayWidth != GrugScreenshots.WIDTH
                || mc.displayHeight != GrugScreenshots.HEIGHT) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr,
                    "Screenshot.equals: the window is "
                            + mc.displayWidth
                            + "x"
                            + mc.displayHeight
                            + ", but screenshot tests are captured at "
                            + GrugScreenshots.WIDTH
                            + "x"
                            + GrugScreenshots.HEIGHT);
            return;
        }

        BufferedImage capture =
                captureRectangle((int) x1, (int) y1, (int) x2, (int) y2, mc.displayHeight);
        if (capture == null) {
            return; // captureRectangle already reported why
        }

        // grug resolves a resource to "<mod name>/<path relative to the mod>", so joining it onto
        // the mods root gives the reference directory on disk. That directory holds numbered PNGs
        // and the assertion passes if the capture matches any of them; see GrugScreenshots.
        File referenceDirectory = new File(getGrugModsDirectory(), referencePath);
        GrugScreenshots.verify(capture, referenceDirectory, referencePath, tolerancePercent);
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
            int x1, int y1, int x2, int y2, int windowHeight) {
        int width = x2 - x1;
        int height = y2 - y1;
        int glY = windowHeight - y2;

        ByteBuffer pixels = ByteBuffer.allocateDirect(width * height * 4);

        try {
            GlBridge.readPixels(x1, glY, width, height, pixels);
        } catch (RuntimeException e) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr,
                    "Screenshot.equals: the pixel readback failed unexpectedly: " + e);
            return null;
        }
        int glError = GlBridge.getError();

        if (glError != GlBridge.noError()) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr,
                    "Screenshot.equals: glReadPixels failed with GL error 0x"
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
                    "Screenshot.equals: could not build an image from the readback: " + e);
            return null;
        }
    }
}
