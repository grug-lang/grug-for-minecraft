package net.grug.minecraft.ornithe;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.grug.minecraft.core.GrugRenderSettle;
import net.grug.minecraft.core.GrugSide;
import net.grug.minecraft.core.GrugTestBox;
import net.grug.minecraft.core.GrugTestPlayerSnap;
import net.grug.minecraft.core.GrugWorldReady;
import net.grug.minecraft.core.ModLoaderAdapter;
import net.grug.minecraft.grug.BlockPos;
import net.grug.minecraft.grug.Grug;
import net.grug.minecraft.grug.GrugBlockEntityNbt;
import net.grug.minecraft.grug.GrugDeclarations;
import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.grug.GrugScreenshots;
import net.grug.minecraft.grug.GrugSlotAccess;
import net.grug.minecraft.grug.GrugVanillaBlocks;
import net.grug.minecraft.grug.Vec3;
import net.grug.minecraft.gui.GrugGuiBuilder;
import net.grug.minecraft.ornithe.block.GrugBlock;
import net.grug.minecraft.ornithe.block.GrugBlockEntityNbtAccess;
import net.grug.minecraft.ornithe.block.GrugBoxRenderer;
import net.grug.minecraft.ornithe.block.entity.GrugBlockEntity;
import net.grug.minecraft.ornithe.client.GrugScreen;
import net.grug.minecraft.ornithe.inventory.DummyCraftingInventory;
import net.grug.minecraft.ornithe.mixin.WorldRendererAccess;
import net.minecraft.block.Block;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.render.world.RenderChunk;
import net.minecraft.client.render.world.WorldRenderer;
import net.minecraft.crafting.CraftingManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.mob.player.PlayerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.HitResult;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class OrnitheAdapter implements ModLoaderAdapter {

    /**
     * This loader's directory under {@code loaders/}, which names the screenshot references it
     * produces.
     */
    private static final String LOADER_NAME = "b1.7.3-ornithe";

    /**
     * This loader's column of the canonical vanilla block name table, so a mod naming a block gets
     * the same block here as it would on every other loader.
     */
    private static final GrugVanillaBlocks.Loader CANONICAL_BLOCKS =
            GrugVanillaBlocks.forLoader(LOADER_NAME);

    /** Block id to name, populated lazily: a reverse lookup scans every block field. */
    private final Map<Integer, String> blockNamesById = new HashMap<>();

    /**
     * The adapter for whichever process this is. Called from {@code GrugModLoader.init()}, which is
     * shared with the Alpha loader and so cannot name this loader's server adapter itself.
     */
    @GrugGenerated(
            "the choice is which process the game is, which a client run only ever picks one of")
    public static ModLoaderAdapter forCurrentEnvironment() {
        return FabricLoader.getInstance().getEnvironmentType() == EnvType.SERVER
                ? new ServerOrnitheAdapter()
                : new OrnitheAdapter();
    }

    @Override
    public File getGameDirectory() {
        return FabricLoader.getInstance().getGameDir().toFile();
    }

    @Override
    public File getGrugModsDirectory() {
        return GrugModLoader.getActiveGrugModsDir();
    }

    @Override
    public String getLoaderName() {
        return LOADER_NAME;
    }

    @Override
    public GrugSide getSide() {
        return GrugSide.CLIENT;
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
        DummyCraftingInventory matrix = new DummyCraftingInventory(inv, (int) startSlot);
        for (int i = 0; i < matrix.getSize(); i++) {
            ItemStack stack = matrix.getItem(i);
            if (stack != null) {
                matrix.removeItem(i, 1);
                applyRecipeRemainder(matrix, i, stack);
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
            if (stack != null && stack.getItem() == item && stack.getDamage() == (int) damage)
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
            if (!GrugSlotAccess.isExtractable(blockEntityObj, i)) continue;
            ItemStack stack = inv.getItem(i);
            if (stack != null && stack.getItem() == item && stack.getDamage() == (int) damage) {
                int extractFromSlot = Math.min(stack.size, remainingToExtract);
                inv.removeItem(i, extractFromSlot);
                remainingToExtract -= extractFromSlot;
            }
        }
        return amount - remainingToExtract;
    }

    @Override
    public double insertItemIntoInventory(
            Object blockEntityObj, Object itemObj, double damage, double amount, boolean insert) {
        Inventory inv = (Inventory) blockEntityObj;
        Item item = (Item) itemObj;
        int meta = (int) damage;
        int maxSize = new ItemStack(item, 1).getMaxSize();
        int remaining = (int) amount;

        // Top up existing stacks first, then use empty slots, like a player's inventory fills. A
        // block entity that declares a rule only has its insertable slots filled, which for an
        // inventory declaring none is all of them. With insert false the count that would fit is
        // answered and nothing is changed, which is how a caller asks whether the inventory has
        // room.
        for (int i = 0; i < inv.getSize() && remaining > 0; i++) {
            if (!GrugSlotAccess.isInsertable(blockEntityObj, i)) continue;
            ItemStack stack = inv.getItem(i);
            if (stack != null && stack.getItem() == item && stack.getDamage() == meta) {
                int room = maxSize - stack.size;
                if (room > 0) {
                    int add = Math.min(room, remaining);
                    if (insert) {
                        stack.size += add;
                    }
                    remaining -= add;
                }
            }
        }

        for (int i = 0; i < inv.getSize() && remaining > 0; i++) {
            if (!GrugSlotAccess.isInsertable(blockEntityObj, i)) continue;
            if (inv.getItem(i) == null) {
                int add = Math.min(maxSize, remaining);
                if (insert) {
                    ItemStack stack = new ItemStack(item, add);
                    stack.setDamage(meta);
                    inv.setItem(i, stack);
                }
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
    public Object getResultStack(Object blockEntityObj) {
        if (!(blockEntityObj instanceof GrugBlockEntity)) return null;
        GrugBlockEntity gbe = (GrugBlockEntity) blockEntityObj;
        return gbe.getResultStack();
    }

    @Override
    public Object getStackItem(Object stackObj) {
        return ((ItemStack) stackObj).getItem();
    }

    @Override
    public double getStackDamage(Object stackObj) {
        return ((ItemStack) stackObj).getDamage();
    }

    @Override
    public double getStackCount(Object stackObj) {
        return ((ItemStack) stackObj).size;
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
        Inventory inv = (Inventory) blockEntityObj;
        Item item = (Item) itemObj;
        int meta = (int) damage;
        int maxSize = new ItemStack(item, 1).getMaxSize();
        ItemStack stack = inv.getItem((int) slot);
        int room;
        if (stack == null) {
            room = maxSize;
        } else if (stack.getItem() == item && stack.getDamage() == meta) {
            room = maxSize - stack.size;
        } else {
            room = 0;
        }
        int add = Math.min(Math.max(room, 0), (int) amount);
        if (commit && add > 0) {
            if (stack == null) {
                ItemStack created = new ItemStack(item, add);
                created.setDamage(meta);
                inv.setItem((int) slot, created);
            } else {
                stack.size += add;
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
        return stack != null ? stack.getDamage() : 0;
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
            stack.setDamage((int) damage);
        }
    }

    @Override
    public void setItemInSlot(Object blockEntityObj, double slot, Object itemObj, double count) {
        Inventory inv = (Inventory) blockEntityObj;
        // item() resolves a name with no matching Item to the block of that name, so a block can
        // arrive
        // here as well as an item.
        ItemStack stack =
                itemObj instanceof Item
                        ? new ItemStack((Item) itemObj, (int) count)
                        : new ItemStack((Block) itemObj, (int) count);
        inv.setItem((int) slot, stack);
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
        Inventory inv = (Inventory) blockEntityObj;
        DummyCraftingInventory matrix = new DummyCraftingInventory(inv, (int) startSlot);
        ItemStack result = CraftingManager.getInstance().getResult(matrix);
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

    /**
     * Places a block and reports it when the game did not put it there.
     *
     * <p>The write can fail without anything going wrong visibly. This version's world is 128
     * blocks tall and {@code World.setBlockQuietly} returns false without writing for y at or above
     * that, and a chunk the client has not received swallows the write too. Both used to surface
     * much later as a fixture that had no block entity, which names the symptom rather than the
     * cause. Reading the block back is what turns the silent drop into an err naming the position
     * that was refused.
     *
     * <p>A write the game accepts and does not leave in place is reported the same way: the caller
     * asked for a block that is not there afterwards. See #151.
     */
    @Override
    public void placeBlock(Object levelObj, double x, double y, double z, String blockName) {
        World world = (World) levelObj;
        String path = blockName.contains(":") ? blockName.split(":", 2)[1] : blockName;

        // air is the one name resolveBlock cannot answer, because this version has no air block to
        // resolve: air is the absence of one, block id 0. Placing it is how a test removes a block,
        // so it skips the lookup and is placed like any other block.
        int blockId = 0;
        if (!path.equals("air")) {
            // The canonical name table already answers under the name this loader spells a block
            // with, so resolveBlock is the whole lookup. See #58.
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
            blockId = targetBlock.id;
        }

        int posX = (int) Math.floor(x);
        int posY = (int) Math.floor(y);
        int posZ = (int) Math.floor(z);

        world.setBlockQuietly(posX, posY, posZ, blockId);

        // A placement that reports false because the block was already there is not a failure,
        // so the block's presence is the check rather than the return value.
        if (world.getBlock(posX, posY, posZ) != blockId) {
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

    @Override
    public long getWorldTime(Object levelObj) {
        return ((World) levelObj).getTime();
    }

    @Override
    public int nextRandomInt(Object levelObj, int bound) {
        return ((World) levelObj).random.nextInt(bound);
    }

    @Override
    public double nextRandomFloat(Object levelObj) {
        return ((World) levelObj).random.nextFloat();
    }

    @Override
    public double nextRandomGaussian(Object levelObj) {
        return ((World) levelObj).random.nextGaussian();
    }

    @Override
    public void explode(Object levelObj, double x, double y, double z, double power) {
        ((World) levelObj).explode(null, x, y, z, (float) power);
    }

    @Override
    public Object getHeldItem(Object playerObj) {
        ItemStack stack = ((PlayerEntity) playerObj).inventory.getSelectedItem();
        return (stack != null) ? stack.getItem() : null;
    }

    @Override
    public void setHeldItem(Object playerObj, Object itemObj) {
        PlayerEntity player = (PlayerEntity) playerObj;
        // item() resolves a name with no matching Item to the block of that name, so a block can
        // arrive here as well as an item.
        ItemStack stack = null;
        if (itemObj instanceof Item) {
            stack = new ItemStack((Item) itemObj);
        } else if (itemObj != null) {
            stack = new ItemStack((Block) itemObj);
        }
        player.inventory.setItem(player.inventory.selectedSlot, stack);
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
    public boolean isAir(Object levelObj, double x, double y, double z) {
        World world = (World) levelObj;
        return world.getBlock((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z)) == 0;
    }

    @Override
    public double countItemEntities(Object levelObj, double x, double y, double z, double radius) {
        World world = (World) levelObj;
        double radiusSquared = radius * radius;
        int count = 0;
        // The game's box query, the same one clear_item_entities removes from, so an item a chunk
        // still holds is counted rather than hidden from the world's own list (#427).
        for (ItemEntity item :
                world.getEntitiesOfType(
                        ItemEntity.class,
                        Box.of(
                                x - radius,
                                y - radius,
                                z - radius,
                                x + radius,
                                y + radius,
                                z + radius))) {
            double dx = item.x - x;
            double dy = item.y - y;
            double dz = item.z - z;
            if (dx * dx + dy * dy + dz * dz <= radiusSquared) {
                count++;
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

        if (block instanceof GrugBlock) {
            GrugBlock grugBlock = (GrugBlock) block;
            net.grug.minecraft.grug.GrugBlockData data =
                    GrugDeclarations.blockDataByFileId.get(grugBlock.blockFileId);
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
                            name =
                                    canonicalName(
                                            GrugVanillaNames.of(field).toLowerCase(Locale.ROOT));
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
                GrugDeclarations.declaredBlocks.entrySet()) {
            if (entry.getKey().endsWith(":" + path) || entry.getKey().equals(path)) {
                Long fileId =
                        GrugDeclarations.blockDataByFileId.entrySet().stream()
                                .filter(e -> e.getValue().id.equals(entry.getKey()))
                                .map(Map.Entry::getKey)
                                .findFirst()
                                .orElse(null);

                if (fileId != null) {
                    for (Block block : Block.BY_ID) {
                        if (block instanceof GrugBlock
                                && ((GrugBlock) block).blockFileId == fileId) {
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
                    String fieldName = GrugVanillaNames.of(field);
                    if (fieldName.equalsIgnoreCase(vanillaPath)
                            || fieldName
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
    public Object getLevel() {
        return MinecraftInstance.get().world;
    }

    @Override
    public Object testPlayer() {
        return MinecraftInstance.get().player;
    }

    @Override
    @GrugGenerated("no player: the run has no tests to build a room for until someone has joined")
    public void buildTestBox(int radius) {
        // The room build is the first thing the runner does for a test, and it dirties the
        // renderer, so any settle answer from before it describes frames this test must not
        // capture. See GrugRenderSettle.reset.
        renderSettle.reset();
        PlayerEntity player = (PlayerEntity) testPlayer();
        if (player == null) return;
        GrugTestBox.build(this, getLevel(), player.x, player.y - 1.62, player.z, radius);
    }

    @Override
    @GrugGenerated("no player: the run has no tests to sweep after until someone has joined")
    public void sweepTestItems() {
        PlayerEntity player = (PlayerEntity) testPlayer();
        if (player == null) return;
        GrugTestBox.sweepItems(this, getLevel(), player.x, player.y - 1.62, player.z);
    }

    @Override
    public void roundTripNbt(Object blockEntityObj) {
        BlockEntity be = (BlockEntity) blockEntityObj;
        NbtCompound nbt = new NbtCompound();
        be.writeNbt(nbt);
        be.readNbt(nbt);
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

    @GrugGenerated(
            "the game's private tick timer: CI sees the scale only as how long a test takes, and"
                    + " a dedicated server, which has no client timer, is never launched")
    @Override
    public boolean setGameSpeed(double speed) {
        // A dedicated server has no client timer: its clock is its own tick loop.
        if (getSide() != GrugSide.CLIENT) return false;
        return GrugClientHooks.setGameSpeed((float) speed);
    }

    @Override
    public void tickBlockEntity(Object blockEntityObj) {
        ((GrugBlockEntity) blockEntityObj).tick();
    }

    @Override
    public void redraw(Object blockEntityObj) {
        BlockEntity be = (BlockEntity) blockEntityObj;
        // The world tells its renderers that the block changed, which marks the chunk around it for
        // compiling again, the same as a block being placed there.
        be.world.notifyBlockChanged(be.x, be.y, be.z);
    }

    @Override
    public BlockPos getBlockPosOfBlockEntity(Object blockEntityObj) {
        BlockEntity be = (BlockEntity) blockEntityObj;
        return new BlockPos(be.x, be.y, be.z);
    }

    @Override
    public Object getItemFromRegistry(Object resourceLocationObj) {
        String path = ((NamespacedIdentifier) resourceLocationObj).identifier();

        Object translation = matchTranslationKey(path);
        if (translation != null) return translation;

        Object vanillaItem = matchVanillaItem(path);
        if (vanillaItem != null) return vanillaItem;

        return matchVanillaBlock(path);
    }

    @GrugGenerated("translation-key lookup: a no-match fallback cannot be forced")
    private Object matchTranslationKey(String path) {
        // Try Translation Keys
        for (Item item : Item.BY_ID) {
            if (item == null) continue;
            String key = item.getTranslationKey();
            if (key != null) {
                if (key.equals(path)
                        || key.endsWith("." + path)
                        || key.equalsIgnoreCase("item." + path)
                        || key.equalsIgnoreCase("tile." + path)) {
                    return item;
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
                String fieldName = GrugVanillaNames.of(field);
                if (fieldName.equalsIgnoreCase(path)
                        || fieldName.replace("_", "").equalsIgnoreCase(path.replace("_", ""))) {
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
                String fieldName = GrugVanillaNames.of(field);
                if (fieldName.equalsIgnoreCase(vanillaPath)
                        || fieldName
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

    @GrugGenerated("recipe remainder: no item in this version carries one")
    private static void applyRecipeRemainder(
            DummyCraftingInventory matrix, int slot, ItemStack stack) {
        if (stack.getItem().hasRecipeRemainder()) {
            matrix.setItem(slot, new ItemStack(stack.getItem().getRecipeRemainder()));
        }
    }

    @Override
    public Object createItemStack(Object itemObj, double damage) {
        ItemStack stack = itemStack(itemObj);
        stack.setDamage((int) damage);
        return stack;
    }

    @Override
    public double getItemEntityDamage(Object itemEntityObj) {
        return ((ItemEntity) itemEntityObj).item.getDamage();
    }

    @Override
    public void clearItemEntities(
            Object levelObj, double x1, double y1, double z1, double x2, double y2, double z2) {
        World world = (World) levelObj;
        // The game's own box query, so the filtering is its branches and not ours: the six
        // comparisons this used to make are twelve branch outcomes, and the suite's few items only
        // ever exercise one side of each, which the coverage gate then reports as missed. It
        // returns a fresh list, so removing from the world's own list while walking it is safe.
        for (ItemEntity item :
                world.getEntitiesOfType(ItemEntity.class, Box.of(x1, y1, z1, x2, y2, z2))) {
            // remove() is the dead flag and the world's list is what count_item_entities and
            // find_item_entity walk, but the box query reads the chunk's own entity lists, so the
            // chunk has to be told too. Without that the item stays in the chunk and a later box
            // query finds it again, which is how a dropped item survived the sweep (#427).
            // 1.2.5-forge
            // already removes from the chunk for the same reason. World.removeEntityNow is still
            // not
            // used: it exists in the named jar but does not resolve at runtime on this loader's
            // toolchain, a NoSuchMethodError as soon as an item is in range (5810ceb3).
            item.remove();
            world.getChunk((int) Math.floor(item.x), (int) Math.floor(item.z)).removeEntity(item);
            world.entities.remove(item);
        }
    }

    @Override
    public Object findItemEntity(Object levelObj, double x, double y, double z, double radius) {
        World world = (World) levelObj;
        double radiusSquared = radius * radius;
        // The same game box query as count_item_entities and clear_item_entities, so all three
        // agree on which item entities exist (#427).
        for (ItemEntity item :
                world.getEntitiesOfType(
                        ItemEntity.class,
                        Box.of(
                                x - radius,
                                y - radius,
                                z - radius,
                                x + radius,
                                y + radius,
                                z + radius))) {
            double dx = item.x - x;
            double dy = item.y - y;
            double dz = item.z - z;
            if (dx * dx + dy * dy + dz * dz <= radiusSquared) {
                return item;
            }
        }
        return null;
    }

    @GrugGenerated("item stack: the block form is not reachable from a grug item")
    private static ItemStack itemStack(Object itemObj) {
        if (itemObj instanceof Item) {
            Item item = (Item) itemObj;
            return new ItemStack(item);
        }
        return new ItemStack((Block) itemObj);
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
        Object level = getLevel();

        // y is the eye reference in this version, so the feet come from the collision box.
        return GrugWorldReady.isReady(
                (x, y, z) -> isAir(level, x, y, z), player.x, player.shape.minY, player.z);
    }

    @Override
    public boolean isWorldSettled() {
        // Asked now as well as per frame: the runner runs up to 10 ticks for a frame, so a change a
        // test just made has not been in a frame yet, and only the renderer's list knows it.
        return renderSettle.settledNow(frameHadPendingWork());
    }

    @Override
    public void forgetSettledFrames() {
        renderSettle.reset();
    }

    @Override
    public void onFrameRendered() {
        renderSettle.onFrame(frameHadPendingWork());
    }

    /**
     * The client's frames, and whether the last one had work in it. See {@link
     * #frameHadPendingWork}.
     */
    private final GrugRenderSettle renderSettle = new GrugRenderSettle();

    /**
     * Whether the frame that just finished still had work in it: a rebuild the renderer did not get
     * to, a window that was not yet at the size a capture reads, or a frame drawn before the world
     * exists (a hotkey run's title screen).
     *
     * <p>This runs from the render path, after the frame was drawn, so the renderer's list is what
     * that frame left behind: a chunk still in it and in view was not drawn, and a frame drawn
     * before the run's window resize took effect lays its GUI out elsewhere. Both are sampled per
     * frame rather than per game tick because the game draws fewer frames than it runs ticks, so a
     * tick-based answer can pass while the frame a capture reads is still the older one. See #257.
     *
     * <p>Which of the cases a run reaches depends on the runner's load: a frame that ran out of
     * time needs a runner loaded enough to starve it, and the resize case needs a frame drawn
     * before the window manager caught up. The idle answer stays measured through the caller's
     * outcome: the screenshot tests pass only when this settles on the frame their capture reads.
     */
    @GrugGenerated(
            "settle: a frame that ran out of time or predates the resize, reached by load only")
    private boolean frameHadPendingWork() {
        Minecraft minecraft = MinecraftInstance.get();
        if (minecraft.width != GrugScreenshots.WIDTH
                || minecraft.height != GrugScreenshots.HEIGHT) {
            return true;
        }
        World world = minecraft.world;
        WorldRenderer worldRenderer = minecraft.worldRenderer;
        PlayerEntity player = minecraft.player;
        if (world == null || worldRenderer == null || player == null) {
            return true;
        }
        // Beta drains at most 500 light updates per call, and a room built in the world queues
        // tens of thousands, so one call per frame left the light busy for dozens of frames after
        // every test's setup, and more with every repeat because the backlog was carried over. The
        // frame's meshes are baked from light, so a capture has to wait for the light to be done,
        // and "done" is the queue being empty: this drains it in full here, which makes the answer
        // "the light has no work left" without a time budget or any other tuned value. See #417.
        //
        // This relies on doLightUpdates() returning false only when the queue is
        // empty. It also returns false without draining anything once it is nested 50 calls deep,
        // and that cannot happen here because nothing the drain runs (LightUpdate.run and
        // World.updateLight) calls it again. If a light update ever starts to nest, this would
        // stop short and report the light as settled while updates are still queued, so look here
        // first if captures start to come out lit differently. The engine ends the loop itself if
        // propagation outruns the drain, by clearing the queue once it passes a million updates.
        while (world.doLightUpdates()) {}
        for (Object entry : ((WorldRendererAccess) worldRenderer).grug$dirtyChunks()) {
            // Only the chunks the renderer is going to rebuild count as pending, and that is the
            // ones it can see within the distance compileChunks itself compiles within: a chunk
            // outside the frustum, or further away than that distance, is kept in this list until
            // the camera turns or moves, so a stationary run would otherwise never settle.
            RenderChunk chunk = (RenderChunk) entry;
            if (chunk.visible
                    && chunk.squaredDistanceToCenter(player) <= COMPILE_DISTANCE_SQUARED) {
                return true;
            }
        }
        return false;
    }

    /**
     * The squared distance {@code WorldRenderer.compileChunks} compiles a chunk within, mirrored
     * from its own check. A chunk further out is only rebuilt once the camera moves closer.
     */
    private static final float COMPILE_DISTANCE_SQUARED = 256.0F;

    @Override
    public boolean registersModRecipes() {
        // GrugRecipeHelper registers the mods' data/<namespace>/recipes tree at startup.
        return true;
    }

    @Override
    public Vec3 getTestOrigin() {
        PlayerEntity player = MinecraftInstance.get().player;
        return new Vec3(player.x, player.y + 3.0, player.z);
    }

    private boolean testPlayerSnapped = false;
    private double savedX, savedY, savedZ;
    private float savedYaw, savedPitch;

    @Override
    @GrugGenerated("screenshot/GL integration: failure paths a healthy run cannot enter")
    public void snapPlayerForTestOnClient() {
        PlayerEntity player = MinecraftInstance.get().player;
        if (player == null) {
            throw Grug.fatal(
                    "The test runner: there is no local player to snap to their block centre.");
        }

        savedX = player.x;
        savedY = player.y;
        savedZ = player.z;
        savedYaw = player.yaw;
        savedPitch = player.pitch;
        testPlayerSnapped = true;

        // Snap the player to the centre of their own block and level the view, so every loader
        // frames a fixture identically. The snap stays inside the player's own block, and therefore
        // inside the chunk already loaded and lit around them; a teleport into a chunk the client
        // has not lit yet crashes this generation of the game.
        // setPositionAndAngles takes the feet, not this version's eye-height player.y, so passing
        // the feet keeps the player's height. Passing the eye would raise them an eye height,
        // which is what this call used to do. The runner restores the saved position when the test
        // ends.
        player.setPositionAndAngles(
                GrugTestPlayerSnap.blockCentre(savedX),
                savedY - 1.62,
                GrugTestPlayerSnap.blockCentre(savedZ),
                0.0F,
                0.0F);
    }

    @Override
    @GrugGenerated("screenshot/GL integration: failure paths a healthy run cannot enter")
    public void restorePlayerAfterTest() {
        // A no-op on a dedicated server, where there is no client player to snap. The runner calls
        // this for every test that ends, so it must not report an error for those.
        if (!testPlayerSnapped) return;
        testPlayerSnapped = false;

        PlayerEntity player = MinecraftInstance.get().player;
        if (player != null) {
            // The saved y is the eye; the feet are what setPositionAndAngles wants, so the restore
            // puts the player back rather than raising them again.
            player.setPositionAndAngles(savedX, savedY - 1.62, savedZ, savedYaw, savedPitch);
        }
    }

    @Override
    @GrugGenerated("screenshot/GL integration: failure paths a healthy run cannot enter")
    public void closeOpenScreenForTest() {
        MinecraftInstance.get().openScreen(null);
    }

    @Override
    public boolean isScreenOpenForTest() {
        return MinecraftInstance.get().screen != null;
    }

    @Override
    @GrugGenerated("screenshot/GL integration: failure paths a healthy run cannot enter")
    public void useBlockForTest(Object levelObj, double x, double y, double z) {
        if (refuseWithoutAClient("Test.use_block")) return;
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

        // World.getBlock() hands back a block id rather than a Block in this generation.
        int blockId = world.getBlock(blockX, blockY, blockZ);
        Block block = (blockId >= 0 && blockId < Block.BY_ID.length) ? Block.BY_ID[blockId] : null;

        if (block instanceof GrugBlock) {
            GrugBlock grugBlock = (GrugBlock) block;
            // Go through the client's own right-click, which asks the block's use first and the
            // held
            // item after, so the test drives the same path a real click does and a block that
            // declines leaves the click to the item.
            MinecraftInstance.get()
                    .interactionManager
                    .useBlock(
                            player,
                            world,
                            player.inventory.getSelectedItem(),
                            blockX,
                            blockY,
                            blockZ,
                            1);
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

    @Override
    @GrugGenerated(
            "failure paths a healthy run cannot enter: no local player or client level, no block at"
                    + " the position, or a shape the overlay failed to reset")
    public boolean miningCrackForTest(Object levelObj, double x, double y, double z) {
        if (refuseWithoutAClient("Test.mining_crack")) return false;
        Minecraft minecraft = MinecraftInstance.get();
        PlayerEntity player = minecraft.player;
        if (player == null) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr, "Test.mining_crack: There is no local player to mine with.");
            return false;
        }

        World world = (World) levelObj;
        int blockX = (int) Math.floor(x);
        int blockY = (int) Math.floor(y);
        int blockZ = (int) Math.floor(z);
        int blockId = world.getBlock(blockX, blockY, blockZ);
        Block block = (blockId > 0 && blockId < Block.BY_ID.length) ? Block.BY_ID[blockId] : null;
        if (block == null) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr,
                    "Test.mining_crack: There is no block at "
                            + blockX
                            + ", "
                            + blockY
                            + ", "
                            + blockZ
                            + ".");
            return false;
        }

        // One overlay pass for the block, the way a frame draws it while the player holds the
        // attack button, at the middle of the break. Drawing between frames goes into the buffer
        // the next frame clears, and the state it leaves is the game's own.
        HitResult hit =
                new HitResult(
                        blockX,
                        blockY,
                        blockZ,
                        1,
                        Vec3d.fromPool(blockX + 0.5, blockY + 0.5, blockZ + 0.5));
        GrugBoxRenderer.resetOverlayProbe();
        float previousMiningProgress = minecraft.worldRenderer.miningProgress;
        minecraft.worldRenderer.miningProgress = 0.5F;
        minecraft.worldRenderer.renderMiningProgress(
                player, hit, 0, player.inventory.getSelectedItem(), 0.0F);
        minecraft.worldRenderer.miningProgress = previousMiningProgress;

        // The shape has to be a full cube again, or every later block of this type in the same
        // chunk compile is reshaped by the last box the overlay drew.
        if (block.minX != 0.0
                || block.minY != 0.0
                || block.minZ != 0.0
                || block.maxX != 1.0
                || block.maxY != 1.0
                || block.maxZ != 1.0) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr,
                    "Test.mining_crack: The overlay left the block at "
                            + blockX
                            + ", "
                            + blockY
                            + ", "
                            + blockZ
                            + " shaped from ("
                            + block.minX
                            + ", "
                            + block.minY
                            + ", "
                            + block.minZ
                            + ") to ("
                            + block.maxX
                            + ", "
                            + block.maxY
                            + ", "
                            + block.maxZ
                            + ") instead of a full cube.");
            return false;
        }

        return GrugBoxRenderer.overlayDrew();
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

        Box area =
                Box.of(
                        blockX + minX,
                        blockY + minY,
                        blockZ + minZ,
                        blockX + maxX,
                        blockY + maxY,
                        blockZ + maxZ);
        ArrayList boxes = new ArrayList();
        block.addCollisions(world, blockX, blockY, blockZ, area, boxes);
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
        World world = (World) levelObj;
        int blockX = (int) Math.floor(x);
        int blockY = (int) Math.floor(y);
        int blockZ = (int) Math.floor(z);
        Block block = blockAt(world, blockX, blockY, blockZ);
        if (block == null) {
            return false;
        }

        // The game itself asks this after the block's state has set its shape, which is what the
        // world relies on before it draws the outline.
        block.updateShape(world, blockX, blockY, blockZ);
        Box box = block.getOutlineShape(world, blockX, blockY, blockZ);
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

    /**
     * The block at a position, or null for air or an id nothing was registered for.
     *
     * <p>The only callers are the two test helpers above, and an id past the table cannot come from
     * a world, so the gate does not measure this lookup.
     */
    @GrugGenerated("test helper: the block lookup the two test helpers above share")
    private static Block blockAt(World world, int x, int y, int z) {
        int blockId = world.getBlock(x, y, z);
        if (blockId <= 0 || blockId >= Block.BY_ID.length) {
            return null;
        }
        return Block.BY_ID[blockId];
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
        File referenceDirectory = new File(GrugModLoader.getActiveGrugModsDir(), referencePath);
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
        Minecraft mc = MinecraftInstance.get();
        if (mc.width != GrugScreenshots.WIDTH || mc.height != GrugScreenshots.HEIGHT) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr,
                    operation
                            + ": the window is "
                            + mc.width
                            + "x"
                            + mc.height
                            + ", but screenshot tests are captured at "
                            + GrugScreenshots.WIDTH
                            + "x"
                            + GrugScreenshots.HEIGHT);
            return null;
        }

        return captureRectangle(operation, (int) x1, (int) y1, (int) x2, (int) y2, mc.height);
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
     * Reads back a rectangle of the frame that's currently on screen. Runs from Minecraft.tick(),
     * i.e. after the previous frame was rendered but before this one is drawn, so the frame of
     * interest is whatever was drawn most recently. On LWJGL 2 that is the back buffer, which is
     * also GL's default read buffer.
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
            GL11.glReadPixels(x1, glY, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
        } catch (RuntimeException e) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr, operation + ": the pixel readback failed unexpectedly: " + e);
            return null;
        }
        int glError = GL11.glGetError();
        if (glError != GL11.GL_NO_ERROR) {
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
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            for (int row = 0; row < height; row++) {
                for (int column = 0; column < width; column++) {
                    int index = (row * width + column) * 4;
                    int r = pixels.get(index) & 0xFF;
                    int g = pixels.get(index + 1) & 0xFF;
                    int b = pixels.get(index + 2) & 0xFF;
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
