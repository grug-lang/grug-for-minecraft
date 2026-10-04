package net.grug.minecraft.ornithe;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.grug.minecraft.core.GrugWorldReady;
import net.grug.minecraft.core.ModLoaderAdapter;
import net.grug.minecraft.grug.BlockPos;
import net.grug.minecraft.grug.Grug;
import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.grug.GrugScreenshots;
import net.grug.minecraft.grug.GrugVanillaBlocks;
import net.grug.minecraft.grug.Vec3;
import net.grug.minecraft.gui.GrugGuiBuilder;
import net.grug.minecraft.ornithe.block.GrugBlock;
import net.grug.minecraft.ornithe.block.entity.GrugBlockEntity;
import net.grug.minecraft.ornithe.client.GrugScreen;
import net.grug.minecraft.ornithe.item.GrugItem;
import net.minecraft.block.Block;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.crafting.CraftingManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.mob.player.PlayerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.world.World;
import net.ornithemc.osl.core.api.util.NamespacedIdentifier;
import net.ornithemc.osl.core.api.util.NamespacedIdentifiers;
import net.ornithemc.osl.lifecycle.api.client.MinecraftInstance;

import org.lwjgl.opengl.GL11;

import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class OrnitheAdapter implements ModLoaderAdapter {

    /**
     * This loader's column of the canonical vanilla block name table, so a mod naming a block gets
     * the same block here as it would on every other loader.
     */
    private static final GrugVanillaBlocks.Loader CANONICAL_BLOCKS =
            GrugVanillaBlocks.forLoader("a1.1.2_01-ornithe");

    /** Block id to name, populated lazily: a reverse lookup scans every block field. */
    private final Map<Integer, String> blockNamesById = new HashMap<>();

    @Override
    public File getGameDirectory() {
        return FabricLoader.getInstance().getGameDir().toFile();
    }

    @Override
    public File getGrugModsDirectory() {
        return GrugModLoader.getActiveGrugModsDir();
    }

    @Override
    public void logInfo(String message) {
        GrugModLoader.LOGGER.info(message);
    }

    @Override
    public void logError(String message) {
        // A contained mod err, and the sandbox's own report of it: the JNI layer clears whatever a
        // script threw and the script carries on, so this is where a failed script call surfaces.
        GrugModLoader.LOGGER.error(message);
    }

    // --- GUI & Inventory Methods ---

    @Override
    @GrugGenerated("client GUI glue: the screen and render glue are excluded")
    public void openGui(Object playerObj, Object blockEntityObj, Object guiBuilderObj) {
        // The player and builder types cannot be wrong because of a script, so cast directly.
        PlayerEntity player = (PlayerEntity) playerObj;
        Inventory inventory = (Inventory) blockEntityObj;
        GrugGuiBuilder builder = (GrugGuiBuilder) guiBuilderObj;
        if (FabricLoader.getInstance().getEnvironmentType() != EnvType.CLIENT) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr,
                    "GUI.open: Opening a GUI on a dedicated server is not supported yet.");
            return;
        }

        GrugScreen.open(player, inventory, builder);
    }

    @Override
    public void consumeCraftingIngredients(Object blockEntityObj, double startSlot) {
        Inventory inv = (Inventory) blockEntityObj;
        // Alpha has no recipe remainders (like the empty bucket from a milk bucket)
        for (int i = 0; i < 9; i++) {
            int slot = (int) startSlot + i;
            if (inv.getItem(slot) != null) {
                inv.removeItem(slot, 1);
            }
        }
    }

    @Override
    public double countItemInInventory(Object blockEntityObj, Object itemObj, double damage) {
        Inventory inv = (Inventory) blockEntityObj;
        Item item = (Item) itemObj;
        int total = 0;
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack != null && stack.getItem() == item && stack.metadata == (int) damage)
                total += stack.size;
        }
        return total;
    }

    @Override
    public void dropInventory(Object levelObj, double x, double y, double z) {
        World world = (World) levelObj;
        BlockEntity be =
                world.getBlockEntity((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
        Inventory inv = (Inventory) be;
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack != null) {
                world.addEntity(new ItemEntity(world, x, y, z, stack));
                inv.setItem(i, null);
            }
        }
    }

    @Override
    public double extractItemFromInventory(
            Object blockEntityObj, Object itemObj, double damage, double amount) {
        Inventory inv = (Inventory) blockEntityObj;
        Item item = (Item) itemObj;
        int remainingToExtract = (int) amount;
        for (int i = 0; i < inv.getSize() && remainingToExtract > 0; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack != null && stack.getItem() == item && stack.metadata == (int) damage) {
                int extractFromSlot = Math.min(stack.size, remainingToExtract);
                inv.removeItem(i, extractFromSlot);
                remainingToExtract -= extractFromSlot;
            }
        }
        return amount - remainingToExtract;
    }

    @Override
    public double insertItemIntoInventory(
            Object blockEntityObj, Object itemObj, double damage, double amount) {
        Inventory inv = (Inventory) blockEntityObj;
        Item item = (Item) itemObj;
        int meta = (int) damage;
        int maxSize = new ItemStack(item, 1).getMaxSize();
        int remaining = (int) amount;

        // Top up existing stacks first, then use empty slots, like a player's inventory fills.
        for (int i = 0; i < inv.getSize() && remaining > 0; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack != null && stack.getItem() == item && stack.metadata == meta) {
                int room = maxSize - stack.size;
                if (room > 0) {
                    int add = Math.min(room, remaining);
                    stack.size += add;
                    remaining -= add;
                }
            }
        }

        for (int i = 0; i < inv.getSize() && remaining > 0; i++) {
            if (inv.getItem(i) == null) {
                int add = Math.min(maxSize, remaining);
                ItemStack stack = new ItemStack(item, add);
                stack.metadata = meta;
                inv.setItem(i, stack);
                remaining -= add;
            }
        }

        return amount - remaining;
    }

    @Override
    public double takeItemFromSlot(Object blockEntityObj, double slot, double amount) {
        Inventory inv = (Inventory) blockEntityObj;
        ItemStack removed = inv.removeItem((int) slot, (int) amount);
        return removed != null ? removed.size : 0;
    }

    @Override
    public double takeCraftingResult(Object blockEntityObj, double amount) {
        if (blockEntityObj instanceof GrugBlockEntity gbe) {
            ItemStack removed = gbe.takeResultStack((int) amount);
            if (removed != null) {
                gbe.notifyOutputTaken(removed.size);
                return removed.size;
            }
            return 0;
        }

        Grug.hostFunctionErrorHappened(
                Grug.statePtr,
                "take_crafting_result: reference result extraction is not implemented for this"
                        + " loader.");
        return 0;
    }

    @Override
    public double getInventorySize(Object blockEntityObj) {
        return ((Inventory) blockEntityObj).getSize();
    }

    @Override
    public double getItemCountInSlot(Object blockEntityObj, double slot) {
        ItemStack stack = ((Inventory) blockEntityObj).getItem((int) slot);
        return stack != null ? stack.size : 0;
    }

    @Override
    public double getItemDamageInSlot(Object blockEntityObj, double slot) {
        ItemStack stack = ((Inventory) blockEntityObj).getItem((int) slot);
        return stack != null ? stack.metadata : 0;
    }

    @Override
    public Object getItemInSlot(Object blockEntityObj, double slot) {
        ItemStack stack = ((Inventory) blockEntityObj).getItem((int) slot);
        return (stack != null) ? stack.getItem() : null;
    }

    @Override
    public void setItemCountInSlot(Object blockEntityObj, double slot, double count) {
        Inventory inv = (Inventory) blockEntityObj;
        ItemStack stack = inv.getItem((int) slot);
        if (stack != null) {
            if (count <= 0) inv.setItem((int) slot, null);
            else stack.size = (int) count;
        }
    }

    @Override
    public void setItemDamageInSlot(Object blockEntityObj, double slot, double damage) {
        ItemStack stack = ((Inventory) blockEntityObj).getItem((int) slot);
        if (stack != null) {
            stack.metadata = (int) damage;
        }
    }

    @Override
    public void setItemInSlot(Object blockEntityObj, double slot, Object itemObj, double count) {
        Inventory inv = (Inventory) blockEntityObj;
        inv.setItem((int) slot, new ItemStack((Item) itemObj, (int) count));
    }

    @Override
    public void updateRecipeOutput(Object blockEntityObj, double startSlot) {
        if (!(blockEntityObj instanceof GrugBlockEntity gbe)) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr,
                    "update_recipe_output: "
                            + blockEntityObj.getClass().getName()
                            + " has no crafting result container.");
            return;
        }
        Inventory inv = (Inventory) blockEntityObj;
        // Alpha recipes match on item ids only, with -1 for an empty cell
        int[] ids = new int[9];
        for (int i = 0; i < 9; i++) {
            ItemStack stack = inv.getItem((int) startSlot + i);
            ids[i] = stack != null ? stack.id : -1;
        }
        ItemStack result = CraftingManager.getInstance().getResult(ids);
        gbe.setResultStack(result != null ? result.copy() : null);
    }

    // --- World & Entity Methods ---

    @Override
    public void setEntityDeltaMovement(Object entityObj, double dx, double dy, double dz) {
        Entity entity = (Entity) entityObj;
        entity.velocityX = dx;
        entity.velocityY = dy;
        entity.velocityZ = dz;
    }

    @Override
    public void spawnEntity(Object levelObj, Object entityObj) {
        ((World) levelObj).addEntity((Entity) entityObj);
    }

    @Override
    public Object getBlockEntity(Object levelObj, double x, double y, double z) {
        return ((World) levelObj)
                .getBlockEntity((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
    }

    @Override
    public void placeBlock(Object levelObj, double x, double y, double z, String blockName) {
        World world = (World) levelObj;
        String path = blockName.contains(":") ? blockName.split(":", 2)[1] : blockName;
        // The canonical name table already answers under the name this loader spells a block with,
        // so resolveBlock is the whole lookup. See #58.
        Block targetBlock = resolveBlock(path);

        if (targetBlock == null) {
            // A name that resolves nowhere is a defect in the mod, not a block to skip: it would
            // leave the mod building against a block it never got, which is the cross loader
            // mismatch the canonical name table exists to remove. The host function error is the
            // report, so there is no second log line.
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr, "place_block: Could not resolve block " + blockName);
            return;
        }

        int posX = (int) Math.floor(x);
        int posY = (int) Math.floor(y);
        int posZ = (int) Math.floor(z);

        world.setBlockQuietly(posX, posY, posZ, targetBlock.id);

        if (targetBlock instanceof net.minecraft.block.BlockWithBlockEntity) {
            targetBlock.onAdded(world, posX, posY, posZ);
        }

        // A placement that reports false because the block was already there is not a failure,
        // so the block's presence is the check rather than the return value. Alpha's world is
        // 128 blocks tall and setBlockQuietly returns false without writing at or above it, and
        // a chunk the client has not received swallows the write too. A write the game accepts
        // and does not leave in place is reported the same way: the caller asked for a block
        // that is not there afterwards. See #151.
        if (world.getBlock(posX, posY, posZ) != targetBlock.id) {
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
     * The name this loader spells a canonical block name with, which is what {@link #resolveBlock}
     * has to be given.
     *
     * <p>Most blocks are spelled the same on every loader, so the table usually answers nothing and
     * the name a mod wrote is what this loader wants. Where Minecraft renamed the block, the table
     * says what this loader calls it now.
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

    @Override
    public boolean hasNeighborSignal(Object levelObj, double x, double y, double z) {
        World world = (World) levelObj;
        return world.hasNeighborSignal(
                (int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
    }

    public String getBlock(Object levelObj, double x, double y, double z) {
        World world = (World) levelObj;
        return blockName(
                world.getBlock((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z)));
    }

    @Override
    public boolean isAir(Object levelObj, double x, double y, double z) {
        World world = (World) levelObj;
        return world.getBlock((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z)) == 0;
    }

    @Override
    public double countItemEntities(Object levelObj, double x, double y, double z, double radius) {
        World world = (World) levelObj;
        double radiusSquared = radius * radius;
        int count = 0;
        for (Object entity : world.entities) {
            if (entity instanceof ItemEntity item) {
                double dx = item.x - x;
                double dy = item.y - y;
                double dz = item.z - z;
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
        world.updateNeighbors((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z), 0);
    }

    @GrugGenerated(
            "block names: an out-of-range id, a grug block with no data and the reflection fallback"
                    + " all cannot be forced from a headless test")
    private String blockName(int id) {
        if (id == 0) {
            return "minecraft:air";
        }

        String cached = blockNamesById.get(id);
        if (cached != null) {
            return cached;
        }

        String name = "minecraft:unknown";
        Block block = (id >= 0 && id < Block.BY_ID.length) ? Block.BY_ID[id] : null;

        if (block instanceof GrugBlock grugBlock) {
            net.grug.minecraft.grug.GrugBlockData data =
                    Grug.blockDataByFileId.get(grugBlock.blockFileId);
            if (data != null) {
                name = data.id;
            }
        } else if (block != null) {
            // Vanilla blocks have no registry on this version, so the name has to come back out of
            // the static fields that resolveBlock searches going the other way. The field name is
            // then mapped through the canonical table so a mod sees the same name it would on any
            // other loader, rather than this loader's own spelling of the block.
            for (Field field : Block.class.getFields()) {
                if (Modifier.isStatic(field.getModifiers())
                        && Block.class.isAssignableFrom(field.getType())) {
                    try {
                        if (field.get(null) == block) {
                            name = canonicalName(field.getName().toLowerCase(Locale.ROOT));
                            break;
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
        }

        blockNamesById.put(id, name);
        return name;
    }

    @GrugGenerated("block resolution: a no-match fallback cannot be forced")
    private Block resolveBlock(String path) {
        Block targetBlock = null;

        // 1. Try resolving custom Grug blocks
        for (Map.Entry<String, net.grug.minecraft.grug.GrugBlockData> entry :
                Grug.declaredBlocks.entrySet()) {
            if (entry.getKey().endsWith(":" + path) || entry.getKey().equals(path)) {
                Long fileId =
                        Grug.blockDataByFileId.entrySet().stream()
                                .filter(e -> e.getValue().id.equals(entry.getKey()))
                                .map(Map.Entry::getKey)
                                .findFirst()
                                .orElse(null);

                if (fileId != null) {
                    for (Block block : Block.BY_ID) {
                        if (block instanceof net.grug.minecraft.ornithe.block.GrugBlock gb
                                && gb.blockFileId == fileId) {
                            targetBlock = block;
                            break;
                        }
                    }
                }
            }
        }

        // 2. Try resolving Vanilla blocks via reflection, under the name this loader spells the
        // canonical name with. A grug block keeps its own name, so the table only ever sees a
        // vanilla one: step 1 has already looked for it under the name the script wrote.
        if (targetBlock == null) {
            String vanillaPath = localName(path);
            for (Field field : Block.class.getFields()) {
                if (Modifier.isStatic(field.getModifiers())
                        && Block.class.isAssignableFrom(field.getType())) {
                    if (field.getName().equalsIgnoreCase(vanillaPath)
                            || field.getName()
                                    .replace("_", "")
                                    .equalsIgnoreCase(vanillaPath.replace("_", ""))) {
                        try {
                            targetBlock = (Block) field.get(null);
                            break;
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
        }

        return targetBlock;
    }

    @Override
    public Object getBlockEntityLevel(Object blockEntityObj) {
        return ((BlockEntity) blockEntityObj).world;
    }

    @Override
    public Object getClientLevel() {
        return MinecraftInstance.get().world;
    }

    @Override
    public Object getPlayer() {
        return MinecraftInstance.get().player;
    }

    @Override
    public void roundTripNbt(Object blockEntityObj) {
        BlockEntity be = (BlockEntity) blockEntityObj;
        NbtCompound nbt = new NbtCompound();
        be.writeNbt(nbt);
        be.readNbt(nbt);
    }

    @Override
    public BlockPos getBlockPosOfBlockEntity(Object blockEntityObj) {
        BlockEntity be = (BlockEntity) blockEntityObj;
        return new BlockPos(be.x, be.y, be.z);
    }

    @Override
    public Object getItemFromRegistry(Object resourceLocationObj) {
        String path = ((NamespacedIdentifier) resourceLocationObj).identifier();

        Object declared = matchDeclared(path);
        if (declared != null) return declared;

        Object vanillaItem = matchVanillaItem(path);
        if (vanillaItem != null) return vanillaItem;

        return matchVanillaBlock(path);
    }

    @GrugGenerated("declared registry match: a no-match fallback cannot be forced per entry")
    private Object matchDeclared(String path) {
        // Match custom grug items first
        for (Map.Entry<String, net.grug.minecraft.grug.GrugItemData> entry :
                Grug.declaredItems.entrySet()) {
            if (entry.getKey().endsWith(":" + path) || entry.getKey().equals(path)) {
                Long fileId =
                        Grug.itemDataByFileId.entrySet().stream()
                                .filter(e -> e.getValue().id.equals(entry.getKey()))
                                .map(Map.Entry::getKey)
                                .findFirst()
                                .orElse(null);
                if (fileId != null) {
                    for (Item item : Item.BY_ID) {
                        if (item instanceof GrugItem gi && gi.itemFileId == fileId) {
                            return item;
                        }
                    }
                }
            }
        }

        // Match custom grug blocks
        for (Map.Entry<String, net.grug.minecraft.grug.GrugBlockData> entry :
                Grug.declaredBlocks.entrySet()) {
            if (entry.getKey().endsWith(":" + path) || entry.getKey().equals(path)) {
                Long fileId =
                        Grug.blockDataByFileId.entrySet().stream()
                                .filter(e -> e.getValue().id.equals(entry.getKey()))
                                .map(Map.Entry::getKey)
                                .findFirst()
                                .orElse(null);
                if (fileId != null) {
                    for (Block block : Block.BY_ID) {
                        if (block instanceof net.grug.minecraft.ornithe.block.GrugBlock gb
                                && gb.blockFileId == fileId) {
                            return Item.BY_ID[block.id];
                        }
                    }
                }
            }
        }

        return null;
    }

    @GrugGenerated("vanilla item reflection: the reflection failure is unreachable")
    private Object matchVanillaItem(String path) {
        // Match Vanilla Items via reflection
        for (Field field : Item.class.getFields()) {
            if (Modifier.isStatic(field.getModifiers())
                    && Item.class.isAssignableFrom(field.getType())) {
                if (field.getName().equalsIgnoreCase(path)
                        || field.getName()
                                .replace("_", "")
                                .equalsIgnoreCase(path.replace("_", ""))) {
                    try {
                        return field.get(null);
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        return null;
    }

    @GrugGenerated("vanilla block reflection: the reflection failure is unreachable")
    private Object matchVanillaBlock(String path) {
        // Match Vanilla Blocks via reflection, under the name this loader spells the canonical name
        // with, so a mod looking up a block's item gets the block it would place.
        String vanillaPath = localName(path);
        for (Field field : Block.class.getFields()) {
            if (Modifier.isStatic(field.getModifiers())
                    && Block.class.isAssignableFrom(field.getType())) {
                if (field.getName().equalsIgnoreCase(vanillaPath)
                        || field.getName()
                                .replace("_", "")
                                .equalsIgnoreCase(vanillaPath.replace("_", ""))) {
                    try {
                        return field.get(null);
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        return null;
    }

    @Override
    public Object createItemEntity(
            Object levelObj, double x, double y, double z, Object itemStackObj) {
        return new ItemEntity((World) levelObj, x, y, z, (ItemStack) itemStackObj);
    }

    @Override
    public Object createItemStack(Object itemObj, double damage) {
        ItemStack stack;
        if (itemObj instanceof Item item) {
            stack = new ItemStack(item);
        } else {
            stack = new ItemStack((Block) itemObj);
        }
        stack.metadata = (int) damage;
        return stack;
    }

    @Override
    public double getItemEntityDamage(Object itemEntityObj) {
        return ((ItemEntity) itemEntityObj).item.metadata;
    }

    @Override
    public Object findItemEntity(Object levelObj, double x, double y, double z, double radius) {
        World world = (World) levelObj;
        double radiusSquared = radius * radius;
        for (Object entity : world.entities) {
            if (entity instanceof ItemEntity item) {
                double dx = item.x - x;
                double dy = item.y - y;
                double dz = item.z - z;
                if (dx * dx + dy * dy + dz * dz <= radiusSquared) {
                    return item;
                }
            }
        }
        return null;
    }

    @Override
    public Object createResourceLocation(String string) {
        if (!string.contains(":")) {
            return NamespacedIdentifiers.from("minecraft", string);
        }
        String[] parts = string.split(":", 2);
        return NamespacedIdentifiers.from(parts[0], parts[1]);
    }

    @Override
    public boolean isWorldReady(Object playerObj) {
        PlayerEntity player = (PlayerEntity) playerObj;
        Object level = getClientLevel();

        // y is the eye reference in this version, so the feet come from the collision box.
        return GrugWorldReady.isReady(
                (x, y, z) -> isAir(level, x, y, z), player.x, player.shape.minY, player.z);
    }

    @Override
    public Vec3 getTestOrigin() {
        PlayerEntity player = MinecraftInstance.get().player;
        return new Vec3(player.x, player.y + 3.0, player.z);
    }

    private boolean graphicsCameraSaved = false;
    private double savedX, savedY, savedZ;
    private float savedYaw, savedPitch;

    @Override
    @GrugGenerated("screenshot/GL integration: failure paths a healthy run cannot enter")
    public Vec3 setupGraphicsTestCamera() {
        PlayerEntity player = MinecraftInstance.get().player;
        if (player == null) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr, "Test.setup_graphics_camera: There is no local player to move.");
            return null;
        }

        savedX = player.x;
        savedY = player.y;
        savedZ = player.z;
        savedYaw = player.yaw;
        savedPitch = player.pitch;
        graphicsCameraSaved = true;

        // Level the view so the test's frame doesn't depend on which way the player happened to be
        // looking, but deliberately do NOT move them somewhere far away.
        //
        // The obvious choice would be a fixed far-off coordinate like 10000,100,10000, so no
        // terrain could bleed into the background. But that reliably crashes Alpha. Teleporting
        // into a chunk the client hasn't lit yet makes World.doLightUpdates recurse through
        // World.updateLight and LightUpdate.run until the stack overflows, which killed the test
        // run roughly one time in four. Staying put keeps the build site inside chunks that are
        // already loaded and lit.
        //
        // Nothing is lost by it: a screenshot test crops a rectangle of the frame, and the GUI is
        // always centred in the window, so the terrain behind it is never part of the comparison.
        player.setPositionAndAngles(player.x, player.y, player.z, 0.0F, 0.0F);

        // +3 mirrors getTestOrigin()'s convention: an origin a little above the player to build on.
        return new Vec3(player.x, player.y + 3.0, player.z);
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

        PlayerEntity player = MinecraftInstance.get().player;
        if (player != null) {
            player.setPositionAndAngles(savedX, savedY, savedZ, savedYaw, savedPitch);
        }
    }

    @Override
    @GrugGenerated("screenshot/GL integration: failure paths a healthy run cannot enter")
    public void closeOpenScreenForTest() {
        MinecraftInstance.get().openScreen(null);
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
        // rather than as a mysterious capture failure. (1300 instead of 130 is an easy mistake to
        // make while writing a new test, and is the whole reason this lists the offending name.)
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

        // A defensive safety net rather than the primary sizing mechanism: R forces 1280x720
        // around a test run. If that ever stops happening, a mismatch here is much easier to
        // diagnose than a screen of subtly wrong pixels.
        Minecraft mc = MinecraftInstance.get();
        if (mc.width != GrugScreenshots.WIDTH || mc.height != GrugScreenshots.HEIGHT) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr,
                    "Screenshot.equals: the window is "
                            + mc.width
                            + "x"
                            + mc.height
                            + ", but screenshot tests are captured at "
                            + GrugScreenshots.WIDTH
                            + "x"
                            + GrugScreenshots.HEIGHT);
            return;
        }

        BufferedImage capture = captureRectangle((int) x1, (int) y1, (int) x2, (int) y2, mc.height);
        if (capture == null) {
            return; // captureRectangle already reported why
        }

        // grug resolves a resource to "<mod name>/<path relative to the mod>", so joining it onto
        // the mods root gives the reference directory on disk. That directory holds numbered PNGs
        // and the assertion passes if the capture matches any of them; see GrugScreenshots.
        File referenceDirectory = new File(GrugModLoader.getActiveGrugModsDir(), referencePath);
        GrugScreenshots.verify(capture, referenceDirectory, referencePath, tolerancePercent);
    }

    /** Returns null if the coordinate is in range, or a message naming it if it isn't. */
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
     * Reads back a rectangle of the frame that's currently on screen.
     *
     * <p>This runs from Minecraft.tick(), i.e. after the previous frame was rendered but before
     * this one is drawn, so the frame of interest is whatever was drawn most recently. On LWJGL 2
     * that is the <em>back</em> buffer, which is also GL's default read buffer, so no glReadBuffer
     * call is needed. Reading the front buffer instead was tried first and returns solid black
     * here: this stack (LWJGL 2 + Mesa under Xvfb) doesn't leave the rendered frame readable
     * through the front buffer at this point in the loop.
     *
     * <p>Coordinate convention, shared with the M cursor-position readout: the rectangle
     * (x1,y1,x2,y2) is in screen space with the origin at the top left, but GL's origin is the
     * bottom left. A screen row {@code y} is therefore GL row {@code height - 1 - y}, which puts
     * the bottom edge of the crop at GL row {@code height - y2}. glReadPixels then fills the buffer
     * bottom row first, so rows are written into the image in reverse to land the right way up.
     */
    @GrugGenerated("screenshot/GL integration: failure paths a healthy run cannot enter")
    private static BufferedImage captureRectangle(
            int x1, int y1, int x2, int y2, int windowHeight) {
        int width = x2 - x1;
        int height = y2 - y1;
        int glY = windowHeight - y2;

        ByteBuffer pixels = ByteBuffer.allocateDirect(width * height * 4);

        try {
            GL11.glReadPixels(x1, glY, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
        } catch (RuntimeException e) {
            // A Java exception escaping into the JNI layer gets described and cleared there, and
            // the script carries on, which would turn a broken capture into a passing test. Report
            // it the way every other problem here is reported, so that the script aborts instead.
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr,
                    "Screenshot.equals: the pixel readback failed unexpectedly: " + e);
            return null;
        }
        int glError = GL11.glGetError();

        if (glError != GL11.GL_NO_ERROR) {
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
            // (LWJGL rewinds it before the read), so its position and limit can't be relied on
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

    @Override
    @GrugGenerated("screenshot/GL integration: failure paths a healthy run cannot enter")
    public void useBlockForTest(Object levelObj, double x, double y, double z) {
        World world = (World) levelObj;
        PlayerEntity player = MinecraftInstance.get().player;
        if (player == null) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr, "Test.use_block: There is no local player to right-click with.");
            return;
        }

        int blockX = (int) Math.floor(x);
        int blockY = (int) Math.floor(y);
        int blockZ = (int) Math.floor(z);

        // In Alpha, World.getBlock() hands back a block id rather than a Block.
        int blockId = world.getBlock(blockX, blockY, blockZ);
        Block block = (blockId >= 0 && blockId < Block.BY_ID.length) ? Block.BY_ID[blockId] : null;

        if (block instanceof GrugBlock grugBlock) {
            // Go through the block's own use() so the test drives the same code path a real
            // right-click does, instead of a copy of the block's GUI layout.
            grugBlock.use(world, blockX, blockY, blockZ, player);
        } else {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr,
                    "Test.use_block: There is no grug block at "
                            + blockX
                            + ", "
                            + blockY
                            + ", "
                            + blockZ
                            + ".");
        }
    }
}
