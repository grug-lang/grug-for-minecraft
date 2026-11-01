package net.grug.minecraft.stationapi;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.grug.minecraft.core.GrugBlockSync;
import net.grug.minecraft.core.GrugRenderSettle;
import net.grug.minecraft.core.GrugSide;
import net.grug.minecraft.core.GrugTestBox;
import net.grug.minecraft.core.GrugTestPlayerSnap;
import net.grug.minecraft.core.GrugWorldReady;
import net.grug.minecraft.core.ModLoaderAdapter;
import net.grug.minecraft.grug.BlockPos;
import net.grug.minecraft.grug.Grug;
import net.grug.minecraft.grug.GrugBlockEntityNbt;
import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.grug.GrugScreenshots;
import net.grug.minecraft.grug.GrugSlotAccess;
import net.grug.minecraft.grug.GrugVanillaBlocks;
import net.grug.minecraft.grug.Vec3;
import net.grug.minecraft.gui.GrugGuiBuilder;
import net.grug.minecraft.stationapi.block.GrugBlock;
import net.grug.minecraft.stationapi.block.GrugBoxRenderer;
import net.grug.minecraft.stationapi.block.entity.GrugBlockEntity;
import net.grug.minecraft.stationapi.client.GrugOverlayHost;
import net.grug.minecraft.stationapi.client.GrugScreenGeometry;
import net.grug.minecraft.stationapi.events.init.InitListener;
import net.grug.minecraft.stationapi.grug.DummyCraftingInventory;
import net.grug.minecraft.stationapi.gui.GrugScreenHandler;
import net.grug.minecraft.stationapi.gui.StationGuiHelper;
import net.grug.minecraft.stationapi.mixin.SoundManagerAccess;
import net.grug.minecraft.stationapi.mixin.WorldRendererAccess;
import net.minecraft.block.Block;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.render.chunk.ChunkBuilder;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.CraftingRecipeManager;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.modificationstation.stationapi.api.gui.screen.container.GuiHelper;
import net.modificationstation.stationapi.api.network.packet.MessagePacket;
import net.modificationstation.stationapi.api.registry.BlockRegistry;
import net.modificationstation.stationapi.api.registry.ItemRegistry;
import net.modificationstation.stationapi.api.util.Identifier;

import org.lwjgl.opengl.GL11;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class StationApiAdapter implements ModLoaderAdapter {

    /**
     * This loader's directory under {@code loaders/}, which names the screenshot references it
     * produces.
     */
    private static final String LOADER_NAME = "b1.7.3-stationapi";

    /**
     * This loader's column of the canonical vanilla block name table, so a mod naming a block gets
     * the same block here as it would on every other loader.
     */
    private static final GrugVanillaBlocks.Loader CANONICAL_BLOCKS =
            GrugVanillaBlocks.forLoader(LOADER_NAME);

    /**
     * What StationAPI's environment type means to grug, answered as a lookup rather than a
     * conditional so the constant has no branch for the coverage gate to have to see both sides of.
     */
    private static final Map<EnvType, GrugSide> SIDES =
            Map.of(EnvType.CLIENT, GrugSide.CLIENT, EnvType.SERVER, GrugSide.DEDICATED_SERVER);

    @Override
    public File getGameDirectory() {
        return FabricLoader.getInstance().getGameDir().toFile();
    }

    @Override
    public File getGrugModsDirectory() {
        return InitListener.getActiveGrugModsDir();
    }

    @Override
    public String getLoaderName() {
        return LOADER_NAME;
    }

    /**
     * StationAPI loads on a dedicated server, so this is the game's own answer rather than an
     * assumption. What a dedicated server then gets from the handles below is not the answer yet:
     * see #165, which is also why this class cannot hold the server-side glue itself. Fabric Loader
     * will not define a class the game marked server-only in a client process, so a class
     * referencing one has to sit behind StationAPI's {@code stationapi:event_bus_server} entrypoint
     * and be reached from a branch nothing on a client resolves.
     */
    @Override
    public GrugSide getSide() {
        return SIDES.get(FabricLoader.getInstance().getEnvironmentType());
    }

    /**
     * Note: Although StationAPI is based on Fabric, it targets Minecraft Beta 1.7.3. Therefore, we
     * do not have access to modern UI abstractions like
     * net.minecraft.screen.SimpleNamedScreenHandlerFactory (or Forge's SimpleMenuProvider).
     * Instead, we manually synchronize the GUI data using StationAPI's MessagePacket system.
     */
    @Override
    @GrugGenerated("client GUI glue: the screen and handler are excluded render glue")
    public void openGui(Object playerObj, Object blockEntityObj, Object guiBuilderObj) {
        PlayerEntity player = (PlayerEntity) playerObj;
        BlockEntity be = (BlockEntity) blockEntityObj;
        GrugGuiBuilder builder = (GrugGuiBuilder) guiBuilderObj;

        if (be instanceof Inventory inv) {
            GuiHelper.openGUI(
                    player,
                    Identifier.of("grug:dynamic_gui"),
                    inv,
                    new GrugScreenHandler(player, inv, builder),
                    messagePacket ->
                            StationGuiHelper.writeBuilderToPacket(
                                    builder,
                                    messagePacket,
                                    packetSyncId(messagePacket),
                                    be.x,
                                    be.y,
                                    be.z));
        }
    }

    @GrugGenerated("gui packet sync id: the packet always carries at least one int")
    private static int packetSyncId(MessagePacket messagePacket) {
        return (messagePacket.ints != null && messagePacket.ints.length > 0)
                ? messagePacket.ints[0]
                : 0;
    }

    // --- Logging Abstraction ---

    @Override
    public void logInfo(String message) {
        InitListener.LOGGER.info(message);
    }

    @Override
    public void logError(String message) {
        // A contained mod err, and the sandbox's own report of it: the JNI layer clears whatever a
        // script threw and the script carries on, so this is where a failed script call surfaces.
        InitListener.LOGGER.error(message);
    }

    // --- Game Functions Abstraction ---

    @Override
    public void setEntityDeltaMovement(Object entityObj, double dx, double dy, double dz) {
        Entity entity = (Entity) entityObj;
        entity.velocityX = dx;
        entity.velocityY = dy;
        entity.velocityZ = dz;
    }

    @Override
    public Vec3 getEntityDeltaMovement(Object entityObj) {
        Entity entity = (Entity) entityObj;
        return new Vec3(entity.velocityX, entity.velocityY, entity.velocityZ);
    }

    @Override
    public void spawnEntity(Object levelObj, Object entityObj) {
        ((World) levelObj).spawnEntity((Entity) entityObj);
    }

    @Override
    public void consumeCraftingIngredients(Object blockEntityObj, double startSlot) {
        Inventory inv = (Inventory) blockEntityObj;
        DummyCraftingInventory matrix = new DummyCraftingInventory(inv, (int) startSlot);
        for (int i = 0; i < matrix.size(); i++) {
            ItemStack stack = matrix.getStack(i);
            if (stack != null) {
                matrix.removeStack(i, 1);
                applyCraftingReturn(matrix, i, stack);
            }
        }
    }

    @GrugGenerated("crafting return: no item in this version carries one")
    private static void applyCraftingReturn(
            DummyCraftingInventory matrix, int slot, ItemStack stack) {
        if (stack.getItem().hasCraftingReturnItem()) {
            matrix.setStack(slot, new ItemStack(stack.getItem().getCraftingReturnItem()));
        }
    }

    @Override
    public double countItemInInventory(Object blockEntityObj, Object itemObj, double damage) {
        Inventory inv = (Inventory) blockEntityObj;
        Item item = (Item) itemObj;
        int total = 0;
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (stack != null && stack.getItem() == item && stack.getDamage() == (int) damage)
                total += stack.count;
        }
        return total;
    }

    @Override
    public void dropInventory(Object levelObj, double x, double y, double z) {
        World world = (World) levelObj;
        BlockEntity be =
                world.getBlockEntity((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
        Inventory inv = (Inventory) be;
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (stack != null) {
                world.spawnEntity(new ItemEntity(world, x, y, z, stack));
                inv.setStack(i, null);
            }
        }
    }

    @Override
    public double extractItemFromInventory(
            Object blockEntityObj, Object itemObj, double damage, double amount) {
        Inventory inv = (Inventory) blockEntityObj;
        Item item = (Item) itemObj;
        int remainingToExtract = (int) amount;
        for (int i = 0; i < inv.size() && remainingToExtract > 0; i++) {
            if (!GrugSlotAccess.isExtractable(blockEntityObj, i)) continue;
            ItemStack stack = inv.getStack(i);
            if (stack != null && stack.getItem() == item && stack.getDamage() == (int) damage) {
                int extractFromSlot = Math.min(stack.count, remainingToExtract);
                inv.removeStack(i, extractFromSlot);
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
        int maxSize = new ItemStack(item, 1, meta).getMaxCount();
        int remaining = (int) amount;

        // Top up existing stacks first, then use empty slots, like a player's inventory fills. A
        // block entity that declares a rule only has its insertable slots filled, which for an
        // inventory declaring none is all of them. With insert false the count that would fit is
        // answered and nothing is changed, which is how a caller asks whether the inventory has
        // room.
        for (int i = 0; i < inv.size() && remaining > 0; i++) {
            if (!GrugSlotAccess.isInsertable(blockEntityObj, i)) continue;
            ItemStack stack = inv.getStack(i);
            if (stack != null && stack.getItem() == item && stack.getDamage() == meta) {
                int room = maxSize - stack.count;
                if (room > 0) {
                    int add = Math.min(room, remaining);
                    if (insert) {
                        stack.count += add;
                    }
                    remaining -= add;
                }
            }
        }

        for (int i = 0; i < inv.size() && remaining > 0; i++) {
            if (!GrugSlotAccess.isInsertable(blockEntityObj, i)) continue;
            if (inv.getStack(i) == null) {
                int add = Math.min(maxSize, remaining);
                if (insert) {
                    inv.setStack(i, new ItemStack(item, add, meta));
                }
                remaining -= add;
            }
        }

        return amount - remaining;
    }

    @Override
    public double takeItemFromSlot(Object blockEntityObj, double slot, double amount) {
        Inventory inv = (Inventory) blockEntityObj;
        ItemStack removed = inv.removeStack((int) slot, (int) amount);
        return removed != null ? removed.count : 0;
    }

    @Override
    public Object getResultStack(Object blockEntityObj) {
        if (!(blockEntityObj instanceof GrugBlockEntity gbe)) return null;
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
        return ((ItemStack) stackObj).count;
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
        int maxSize = new ItemStack(item, 1, meta).getMaxCount();
        ItemStack stack = inv.getStack((int) slot);
        int room;
        if (stack == null) {
            room = maxSize;
        } else if (stack.getItem() == item && stack.getDamage() == meta) {
            room = maxSize - stack.count;
        } else {
            room = 0;
        }
        int add = Math.min(Math.max(room, 0), (int) amount);
        if (commit && add > 0) {
            if (stack == null) {
                inv.setStack((int) slot, new ItemStack(item, add, meta));
            } else {
                stack.count += add;
            }
        }
        return add;
    }

    @Override
    public double takeCraftingResult(Object blockEntityObj, double amount) {
        if (blockEntityObj instanceof GrugBlockEntity gbe) {
            ItemStack removed = gbe.takeResultStack((int) amount);
            if (removed != null) {
                gbe.notifyOutputTaken(removed.count);
                return removed.count;
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
    public Object getBlockEntity(Object levelObj, double x, double y, double z) {
        return ((World) levelObj)
                .getBlockEntity((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
    }

    @Override
    public Object getBlockEntityLevel(Object blockEntityObj) {
        return ((BlockEntity) blockEntityObj).world;
    }

    @GrugGenerated(
            "the dedicated-server refusal can only be taken on a server, and CI launches none until"
                + " #165. The client path below is this loader's own way of reaching the client,"
                + " because Fabric Loader hands it back as an Object.")
    @Override
    public Object getLevel() {
        if (refusesOnADedicatedServer("Test.get_client_level")) return null;

        @SuppressWarnings("deprecation")
        net.minecraft.client.Minecraft mc =
                (net.minecraft.client.Minecraft) FabricLoader.getInstance().getGameInstance();
        return mc.world;
    }

    @GrugGenerated(
            "the dedicated-server refusal can only be taken on a server, and CI launches none until"
                + " #165. The client path below is this loader's own way of reaching the client,"
                + " because Fabric Loader hands it back as an Object.")
    @Override
    public Object testPlayer() {
        if (refusesOnADedicatedServer("Test.get_player")) return null;

        @SuppressWarnings("deprecation")
        net.minecraft.client.Minecraft mc =
                (net.minecraft.client.Minecraft) FabricLoader.getInstance().getGameInstance();
        return mc.player;
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
        // y is the eye reference in this version, so the feet come from the collision box. The room
        // is anchored to the feet: built around the eye, the block the player stands on lands
        // inside
        // their legs, they fall through it, and every test drops them a little further.
        GrugTestBox.build(this, getLevel(), player.x, player.boundingBox.minY, player.z, radius);
    }

    /**
     * Refuses the level, the player and the test origin on a dedicated server until #165 gives this
     * loader a server-side adapter, and says whether it refused.
     *
     * <p>A host function error rather than a fatal, because it is the same bounded defect as a
     * missing level or a missing player: the mod asked for something this side cannot give, the
     * test that asked fails, and a server shared with other players keeps running. A fatal here
     * would take down every player on it for one test's mistake.
     *
     * <p>Each caller then reports a second, more generic message naming its own function: {@code
     * Test.get_client_level} has no level, {@code Test.get_player} has no player, and {@code
     * Test.get_origin} has no test player to measure an origin from. Two messages for one mistake
     * is the price of keeping this one here rather than in every caller.
     */
    @GrugGenerated("the dedicated-server answer is #165, which no CI run exercises until then")
    private boolean refusesOnADedicatedServer(String function) {
        if (getSide() != GrugSide.DEDICATED_SERVER) return false;

        Grug.hostFunctionErrorHappened(
                Grug.statePtr,
                function
                        + ": grug has no dedicated-server adapter on StationAPI yet, so a dedicated"
                        + " server cannot answer for its level, its player or its test origin."
                        + " See #165.");
        return true;
    }

    @Override
    @GrugGenerated("no player: the run has no tests to sweep after until someone has joined")
    public void sweepTestItems() {
        PlayerEntity player = (PlayerEntity) testPlayer();
        if (player == null) return;
        GrugTestBox.sweepItems(this, getLevel(), player.x, player.boundingBox.minY, player.z);
    }

    @Override
    public void roundTripNbt(Object blockEntityObj) {
        net.minecraft.block.entity.BlockEntity be =
                (net.minecraft.block.entity.BlockEntity) blockEntityObj;
        net.minecraft.nbt.NbtCompound nbt = new net.minecraft.nbt.NbtCompound();
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
        ((net.grug.minecraft.stationapi.block.entity.GrugBlockEntity) blockEntityObj).tick();
    }

    @Override
    public void redraw(Object blockEntityObj) {
        net.minecraft.block.entity.BlockEntity be =
                (net.minecraft.block.entity.BlockEntity) blockEntityObj;
        // The world tells its renderers that the block changed, which marks the chunk around it for
        // compiling again, the same as a block being placed there.
        be.world.blockUpdateEvent(be.x, be.y, be.z);
    }

    @Override
    public BlockPos getBlockPosOfBlockEntity(Object blockEntityObj) {
        BlockEntity be = (BlockEntity) blockEntityObj;
        return new BlockPos(be.x, be.y, be.z);
    }

    @Override
    public double getInventorySize(Object blockEntityObj) {
        return ((Inventory) blockEntityObj).size();
    }

    @Override
    public double getItemCountInSlot(Object blockEntityObj, double slot) {
        ItemStack stack = ((Inventory) blockEntityObj).getStack((int) slot);
        return stack != null ? stack.count : 0;
    }

    @Override
    public double getItemDamageInSlot(Object blockEntityObj, double slot) {
        ItemStack stack = ((Inventory) blockEntityObj).getStack((int) slot);
        return stack != null ? stack.getDamage() : 0;
    }

    @Override
    public Object getItemInSlot(Object blockEntityObj, double slot) {
        ItemStack stack = ((Inventory) blockEntityObj).getStack((int) slot);
        return (stack != null) ? stack.getItem() : null;
    }

    @Override
    public Object getItemFromRegistry(Object resourceLocationObj) {
        Identifier id = (Identifier) resourceLocationObj;
        Object item = ItemRegistry.INSTANCE.get(id);
        if (item != null) return item;

        // A canonical block name is also the name a mod expects its item form under, and the other
        // four loaders answer it with the block the name places. StationAPI's item registry spells
        // vanilla block items with this version's own name, so consult the block table the same way
        // place_block does before giving up.
        if (isVanilla(id.getNamespace() + ":" + id.getPath())) {
            Block block =
                    BlockRegistry.INSTANCE.get(
                            Identifier.of("minecraft:" + localName(id.getPath())));
            if (block != null) {
                return Item.ITEMS[block.id];
            }
        }
        return null;
    }

    @Override
    public Object createItemEntity(
            Object levelObj, double x, double y, double z, Object itemStackObj) {
        return new ItemEntity(
                (World) levelObj, (float) x, (float) y, (float) z, (ItemStack) itemStackObj);
    }

    @Override
    public Object createItemStack(Object itemObj, double damage, double count) {
        ItemStack stack = new ItemStack((Item) itemObj, (int) count);
        stack.setDamage((int) damage);
        return stack;
    }

    @Override
    public int maxItemStackCount() {
        // writeNbt writes Count as one byte, and readNbt reads it back as a signed int.
        return Byte.MAX_VALUE;
    }

    @Override
    public double getItemEntityDamage(Object itemEntityObj) {
        return ((ItemEntity) itemEntityObj).stack.getDamage();
    }

    @Override
    public double getItemEntityCount(Object itemEntityObj) {
        return ((ItemEntity) itemEntityObj).stack.count;
    }

    @Override
    public double getItemEntityPickupDelay(Object itemEntityObj) {
        return ((ItemEntity) itemEntityObj).pickupDelay;
    }

    @Override
    public void setItemEntityPickupDelay(Object itemEntityObj, int ticks) {
        ((ItemEntity) itemEntityObj).pickupDelay = ticks;
    }

    @Override
    public void clearItemEntities(
            Object levelObj, double x1, double y1, double z1, double x2, double y2, double z2) {
        World world = (World) levelObj;
        // The game's own box query, so the filtering is its branches and not ours: the six
        // comparisons this used to make are twelve branch outcomes, and the suite's few items only
        // ever exercise one side of each, which the coverage gate then reports as missed. It
        // returns a fresh list, so removing from the world's own list while walking it is safe.
        for (Object entity :
                world.collectEntitiesByClass(
                        ItemEntity.class, Box.create(x1, y1, z1, x2, y2, z2))) {
            ItemEntity item = (ItemEntity) entity;
            // markDead() is the dead flag and the world's list is what count_item_entities and
            // find_item_entity walk, but collectEntitiesByClass reads the chunk's own entity lists,
            // so the chunk has to be told too. Without that the item stays in the chunk and a later
            // query finds it again, which is how a dropped item survived the sweep (#427).
            // 1.2.5-forge already removes from the chunk for the same reason.
            item.markDead();
            world.getChunkFromPos((int) Math.floor(item.x), (int) Math.floor(item.z))
                    .removeEntity(item);
            world.entities.remove(item);
        }
    }

    @Override
    public Object findItemEntity(Object levelObj, double x, double y, double z, double radius) {
        World world = (World) levelObj;
        double radiusSquared = radius * radius;
        // The same game box query as count_item_entities and clear_item_entities, so all three
        // agree on which item entities exist (#427).
        for (Object entity :
                world.collectEntitiesByClass(
                        ItemEntity.class,
                        Box.create(
                                x - radius,
                                y - radius,
                                z - radius,
                                x + radius,
                                y + radius,
                                z + radius))) {
            ItemEntity item = (ItemEntity) entity;
            double dx = item.x - x;
            double dy = item.y - y;
            double dz = item.z - z;
            if (dx * dx + dy * dy + dz * dz <= radiusSquared) {
                return item;
            }
        }
        return null;
    }

    @Override
    public Object createResourceLocation(String string) {
        return Identifier.of(string);
    }

    @Override
    public void setItemCountInSlot(Object blockEntityObj, double slot, double count) {
        Inventory inv = (Inventory) blockEntityObj;
        ItemStack stack = inv.getStack((int) slot);
        if (stack != null) {
            if (count <= 0) inv.setStack((int) slot, null);
            else stack.count = (int) count;
        }
    }

    @Override
    public void setItemDamageInSlot(Object blockEntityObj, double slot, double damage) {
        ItemStack stack = ((Inventory) blockEntityObj).getStack((int) slot);
        if (stack != null) {
            stack.setDamage((int) damage);
        }
    }

    @Override
    public void setItemInSlot(Object blockEntityObj, double slot, Object itemObj, double count) {
        Inventory inv = (Inventory) blockEntityObj;
        inv.setStack((int) slot, new ItemStack((Item) itemObj, (int) count));
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
        DummyCraftingInventory matrix = new DummyCraftingInventory(inv, (int) startSlot);
        ItemStack result = CraftingRecipeManager.getInstance().craft(matrix);
        gbe.setResultStack(result != null ? result.copy() : null);
    }

    @Override
    public void placeBlock(Object levelObj, double x, double y, double z, String blockName) {
        // A Level argument cannot be wrong because of a script, so cast directly.
        placeBlockIn((World) levelObj, x, y, z, blockName);
    }

    /**
     * Places a block and reports it when the game did not put it there.
     *
     * <p>The write can fail without anything going wrong visibly. This version's world is 128
     * blocks tall and {@code World.setBlock} returns false without writing for y at or above that,
     * and a chunk the client has not received swallows the write too. Both used to surface much
     * later as a fixture that had no block entity, which names the symptom rather than the cause.
     * Reading the block back is what turns the silent drop into an err naming the position that was
     * refused.
     *
     * <p>A write the game accepts and does not leave in place is reported the same way: the caller
     * asked for a block that is not there afterwards. See #151.
     */
    private void placeBlockIn(World world, double x, double y, double z, String blockName) {
        Identifier id =
                Identifier.of(blockName.contains(":") ? blockName : "minecraft:" + blockName);

        // air is the one name the registry cannot answer for the same reason the others cannot:
        // there is no air block in this version, air is block id 0. Placing it is how a test
        // removes a block, so it skips the lookup and is placed like any other block.
        int blockId = 0;
        if (!id.getPath().equals("air")) {
            // StationAPI registers a name for some vanilla blocks that means a different block than
            // it does elsewhere (its redstone_torch is the unlit torch, while the other four
            // loaders call the lit one that), so the table is consulted before the registry rather
            // than after it: the name a script wrote often does resolve here, just to the wrong
            // block. A grug block keeps its own namespace, so it never goes through the table.
            Block targetBlock =
                    isVanilla(blockName)
                            ? BlockRegistry.INSTANCE.get(
                                    Identifier.of("minecraft:" + localName(id.getPath())))
                            : null;
            if (targetBlock == null) {
                targetBlock = BlockRegistry.INSTANCE.get(id);
            }

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

        world.setBlock(posX, posY, posZ, blockId);

        // A placement that reports false because the block was already there is not a failure,
        // so the block's presence is the check rather than the return value.
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
        // The client and the server share this loader's world, so the block is there, but the
        // settle wait still has to be told that a frame has to see it. See #804.
        blockSync.owe(posX, posY, posZ, getBlock(world, posX, posY, posZ));
    }

    /**
     * Whether a name a script wrote is a vanilla one, and so the block it names is one the
     * canonical table has an opinion about. A name with no namespace means {@code minecraft}, which
     * is what every loader's resolver assumes.
     */
    private static boolean isVanilla(String blockName) {
        return !blockName.contains(":") || blockName.startsWith("minecraft:");
    }

    /**
     * The name this loader spells a canonical block name with, which is what the block registry has
     * to be asked for.
     *
     * <p>StationAPI names Beta's blocks with modern names already, so the table usually answers
     * nothing. The blocks it does answer for are the ones it disagrees with the other loaders on,
     * the lit redstone torch being the one a mod is most likely to name.
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
        ((World) levelObj).createExplosion(null, x, y, z, (float) power);
    }

    @Override
    public Object getHeldItem(Object playerObj) {
        ItemStack stack = ((PlayerEntity) playerObj).inventory.getSelectedItem();
        return (stack != null) ? stack.getItem() : null;
    }

    @Override
    public void setHeldItem(Object playerObj, Object itemObj) {
        PlayerEntity player = (PlayerEntity) playerObj;
        player.inventory.setStack(
                player.inventory.selectedSlot,
                itemObj == null ? null : new ItemStack((Item) itemObj));
    }

    @Override
    public void clearInventoryOf(Object playerObj) {
        PlayerEntity player = (PlayerEntity) playerObj;
        Inventory inventory = player.inventory;
        for (int slot = 0; slot < inventory.size(); slot++) {
            inventory.setStack(slot, null);
        }
        player.inventory.selectedSlot = 0;
    }

    @Override
    public double getHeldCount(Object playerObj) {
        ItemStack stack = ((PlayerEntity) playerObj).inventory.getSelectedItem();
        return stack == null ? 0.0 : stack.count;
    }

    /** The items this loader lists, in item id order, built once because the list never changes. */
    private List<Item> itemsByIdCache = null;

    private List<Item> itemsById() {
        if (itemsByIdCache == null) {
            List<Item> items = new ArrayList<>();
            for (int id = 0; id < Item.ITEMS.length; id++) {
                Item item = Item.ITEMS[id];
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
        return ((Item) itemObj).getMaxCount();
    }

    @Override
    public void giveItemToPlayer(Object playerObj, Object itemObj) {
        giveItemToPlayer((PlayerEntity) playerObj, (Item) itemObj, 1);
    }

    @Override
    public void giveItemStackToPlayer(Object playerObj, Object itemObj) {
        Item item = (Item) itemObj;
        giveItemToPlayer((PlayerEntity) playerObj, item, item.getMaxCount());
    }

    /**
     * Adds the stack to the player's inventory, dropping whatever does not fit at their feet. Beta
     * has no give command and its client is the server in singleplayer, so the inventory write is
     * the give.
     */
    @GrugGenerated("the null-player arm: a run gives to the player it just cleared")
    private void giveItemToPlayer(PlayerEntity player, Item item, int count) {
        if (player == null) return;
        ItemStack stack = new ItemStack(item, count);
        // StationAPI's addStack returns true as soon as any part of the stack fits, and leaves the
        // rest in stack.count. The return value does not say whether all of it fit, so the
        // remainder is dropped whatever the add answered, as the reference does.
        player.inventory.addStack(stack);
        if (stack.count > 0) {
            player.dropItem(stack, false);
        }
    }

    @Override
    @GrugGenerated("the no-player arm: a run reads the cursor of the player it just cleared")
    public Object carriedItem() {
        PlayerEntity player = (PlayerEntity) testPlayer();
        if (player == null) return null;
        ItemStack stack = player.inventory.getCursorStack();
        return stack == null ? null : stack.getItem();
    }

    @Override
    @GrugGenerated("the no-player arm: a run sets the cursor of the player it just cleared")
    public void setCarriedItem(Object itemObj) {
        PlayerEntity player = (PlayerEntity) testPlayer();
        if (player == null) return;
        player.inventory.setCursorStack(itemObj == null ? null : new ItemStack((Item) itemObj));
    }

    @Override
    @GrugGenerated("the no-player arm: a run clears the cursor of the player it just cleared")
    public void deleteCarriedItem() {
        PlayerEntity player = (PlayerEntity) testPlayer();
        if (player == null) return;
        player.inventory.setCursorStack(null);
    }

    @Override
    public boolean hasSound(String path) {
        return ((SoundManagerAccess) minecraft().soundManager).grug$sounds().get(path) != null;
    }

    @Override
    public void playUiSound(String path, double volume, double pitch) {
        minecraft().soundManager.playSound(path, (float) volume, (float) pitch);
    }

    @SuppressWarnings("deprecation")
    private static net.minecraft.client.Minecraft minecraft() {
        return (net.minecraft.client.Minecraft) FabricLoader.getInstance().getGameInstance();
    }

    @Override
    public boolean hasNeighborSignal(Object levelObj, double x, double y, double z) {
        World world = (World) levelObj;
        return world.isPowered((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
    }

    public String getBlock(Object levelObj, double x, double y, double z) {
        World world = (World) levelObj;
        int id = world.getBlockId((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));

        if (id == 0) {
            return "minecraft:air";
        }

        return blockName(id);
    }

    @GrugGenerated("block names: an out-of-range id and an unregistered block cannot be forced")
    private String blockName(int id) {
        Block block = (id >= 0 && id < Block.BLOCKS.length) ? Block.BLOCKS[id] : null;
        if (block == null) {
            return "minecraft:unknown";
        }

        Identifier key = BlockRegistry.INSTANCE.getId(block);
        if (key == null) {
            return "minecraft:unknown";
        }

        // Mapped through the canonical table so a mod sees the same name it would on any other
        // loader, rather than the name StationAPI registered the block under. A grug block keeps
        // its
        // own namespace, which the table knows nothing about.
        return isVanilla(key.toString()) ? canonicalName(key.getPath()) : key.toString();
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
        // The game's box query, the same one clear_item_entities removes from, so an item a chunk
        // still holds is counted rather than hidden from the world's own list (#427).
        for (Object entity :
                world.collectEntitiesByClass(
                        ItemEntity.class,
                        Box.create(
                                x - radius,
                                y - radius,
                                z - radius,
                                x + radius,
                                y + radius,
                                z + radius))) {
            ItemEntity item = (ItemEntity) entity;
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
        world.notifyNeighbors((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z), 0);
    }

    @Override
    public boolean isWorldReady(Object playerObj) {
        PlayerEntity player = (PlayerEntity) playerObj;
        Object level = getLevel();

        // y is the eye reference in this version, so the feet come from the collision box.
        return GrugWorldReady.isReady(
                (x, y, z) -> isAir(level, x, y, z), player.x, player.boundingBox.minY, player.z);
    }

    @Override
    public boolean isWorldSettled() {
        // What the client owes is the simulated world's current block, not the one the test placed.
        Object simulated = getLevel();
        // Asked now as well as per frame: the runner runs up to 10 ticks for a frame, so a change a
        // test just made has not been in a frame yet, and only the renderer's list knows it.
        // The client and the server share this loader's world, so a placed block is there, but the
        // settlement still waits for a frame that shows it. Both answers are combined inside
        // GrugRenderSettle, whose unit test covers every combination, so no branch here depends on
        // whether an edit happened to be owed. The block answer refreshes from the world it is
        // given first, so it compares the client against what the world holds now. See #804.
        return renderSettle.settledNow(
                frameHadPendingWork(),
                blockSync.settled((x, y, z) -> getBlock(simulated, x, y, z)));
    }

    @Override
    public void forgetSettledFrames() {
        renderSettle.reset();
    }

    @Override
    public void onFrameRendered() {
        @SuppressWarnings("deprecation")
        Minecraft minecraft = (Minecraft) FabricLoader.getInstance().getGameInstance();
        // The reader only runs while an edit is owed, and only a placement in a level records one,
        // so there is a world whenever it is asked. A frame drawn before a world exists owes
        // nothing and answers without reading.
        boolean blocksPending = blockSync.onFrame((x, y, z) -> getBlock(minecraft.world, x, y, z));
        renderSettle.onFrame(frameHadPendingWork(), blocksPending);
    }

    /**
     * The client's frames, and whether the last one had work in it. See {@link
     * #frameHadPendingWork}.
     */
    private final GrugRenderSettle renderSettle = new GrugRenderSettle();

    /**
     * The client's copy of the blocks a test has placed, for the frame hook to sample. See #804.
     */
    private final GrugBlockSync blockSync = new GrugBlockSync();

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
        @SuppressWarnings("deprecation")
        Minecraft minecraft = (Minecraft) FabricLoader.getInstance().getGameInstance();
        if (minecraft.displayWidth != GrugScreenshots.WIDTH
                || minecraft.displayHeight != GrugScreenshots.HEIGHT) {
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
        // This relies on doLightingUpdates() returning false only when the queue is
        // empty. It also returns false without draining anything once it is nested 50 calls deep,
        // and that cannot happen here because nothing the drain runs (LightUpdate.updateLight and
        // World.updateLight) calls it again. If a light update ever starts to nest, this would
        // stop short and report the light as settled while updates are still queued, so look here
        // first if captures start to come out lit differently. The engine ends the loop itself if
        // propagation outruns the drain, by clearing the queue once it passes a million updates.
        while (world.doLightingUpdates()) {}
        for (Object entry : ((WorldRendererAccess) worldRenderer).grug$dirtyChunks()) {
            // Only the chunks the renderer is going to rebuild count as pending, and that is the
            // ones it can see within the distance compileChunks itself compiles within: a chunk
            // outside the frustum, or further away than that distance, is kept in this list until
            // the camera turns or moves, so a stationary run would otherwise never settle.
            ChunkBuilder chunk = (ChunkBuilder) entry;
            if (chunk.inFrustum && chunk.squaredDistanceTo(player) <= COMPILE_DISTANCE_SQUARED) {
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
        // InitListener hands the mods' data/<namespace>/recipes tree to StationAPI to parse.
        return true;
    }

    /**
     * Refuses by the name the mod called, before the default derivation asks {@link #testPlayer()},
     * whose own refusal would name {@code Test.get_player} instead.
     */
    @GrugGenerated("the refusal arm needs a dedicated server, which no CI run launches until #165")
    @Override
    public Vec3 getTestOrigin() {
        if (refusesOnADedicatedServer("Test.get_origin")) return null;

        return ModLoaderAdapter.super.getTestOrigin();
    }

    @Override
    public Vec3 testPlayerEye(Object playerObj) {
        PlayerEntity player = (PlayerEntity) playerObj;
        return new Vec3(player.x, player.y, player.z);
    }

    private boolean testPlayerSnapped = false;
    private double savedX, savedY, savedZ;
    private float savedYaw, savedPitch;

    @Override
    @GrugGenerated("screenshot/GL integration: failure paths a healthy run cannot enter")
    public void snapPlayerForTestOnClient() {
        @SuppressWarnings("deprecation")
        net.minecraft.client.Minecraft mc =
                (net.minecraft.client.Minecraft) FabricLoader.getInstance().getGameInstance();
        PlayerEntity player = mc.player;
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
        // has not lit yet crashes this generation of the game. The runner restores the saved
        // position when the test ends.
        player.setPositionAndAngles(
                GrugTestPlayerSnap.blockCentre(savedX),
                savedY,
                GrugTestPlayerSnap.blockCentre(savedZ),
                0.0F,
                0.0F);
    }

    @Override
    @GrugGenerated("screenshot/GL integration: failure paths a healthy run cannot enter")
    public void restorePlayerAfterTest() {
        // A no-op when the snap never ran, which is every test on a side with no client player.
        // The runner calls this for every test that ends, so it must not report an error for those.
        if (!testPlayerSnapped) return;
        testPlayerSnapped = false;

        @SuppressWarnings("deprecation")
        net.minecraft.client.Minecraft mc =
                (net.minecraft.client.Minecraft) FabricLoader.getInstance().getGameInstance();
        if (mc.player != null) {
            mc.player.setPositionAndAngles(savedX, savedY, savedZ, savedYaw, savedPitch);
        }
    }

    @Override
    @GrugGenerated("screenshot/GL integration: failure paths a healthy run cannot enter")
    public void closeOpenScreenForTest() {
        @SuppressWarnings("deprecation")
        net.minecraft.client.Minecraft mc =
                (net.minecraft.client.Minecraft) FabricLoader.getInstance().getGameInstance();
        mc.setScreen(null);
    }

    @Override
    public boolean isScreenOpenForTest() {
        @SuppressWarnings("deprecation")
        net.minecraft.client.Minecraft mc =
                (net.minecraft.client.Minecraft) FabricLoader.getInstance().getGameInstance();
        return mc.currentScreen != null;
    }

    @Override
    public void openInventory(Object playerObj) {
        @SuppressWarnings("deprecation")
        net.minecraft.client.Minecraft mc =
                (net.minecraft.client.Minecraft) FabricLoader.getInstance().getGameInstance();
        mc.setScreen(new InventoryScreen((PlayerEntity) playerObj));
    }

    @Override
    @GrugGenerated("the no-screen arm: a run presses while the screen it just opened is up")
    public boolean pressScreenKey(int key, boolean ctrl, boolean release) {
        @SuppressWarnings("deprecation")
        net.minecraft.client.Minecraft mc =
                (net.minecraft.client.Minecraft) FabricLoader.getInstance().getGameInstance();
        if (mc.currentScreen == null) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr, "Test.press_screen_key: There is no screen open to press.");
            return false;
        }
        return GrugOverlayHost.pressKey(mc.currentScreen, key, ctrl, release);
    }

    @Override
    @GrugGenerated("the no-screen arm: a run presses while the screen it just opened is up")
    public boolean clickScreen(double x, double y, double button) {
        @SuppressWarnings("deprecation")
        net.minecraft.client.Minecraft mc =
                (net.minecraft.client.Minecraft) FabricLoader.getInstance().getGameInstance();
        if (mc.currentScreen == null) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr, "Test.click_screen: There is no screen open to click.");
            return false;
        }
        if (mc.currentScreen instanceof GrugScreenGeometry geometry) {
            return geometry.grug$click((int) x, (int) y, (int) button);
        }
        return false;
    }

    @Override
    @GrugGenerated("the no-screen arm: a run measures the screen it just opened")
    public double screenWidthForTest() {
        @SuppressWarnings("deprecation")
        net.minecraft.client.Minecraft mc =
                (net.minecraft.client.Minecraft) FabricLoader.getInstance().getGameInstance();
        return mc.currentScreen == null ? 0.0 : mc.currentScreen.width;
    }

    @Override
    @GrugGenerated("the no-screen arm: a run measures the screen it just opened")
    public double guiLeftForTest() {
        @SuppressWarnings("deprecation")
        net.minecraft.client.Minecraft mc =
                (net.minecraft.client.Minecraft) FabricLoader.getInstance().getGameInstance();
        if (mc.currentScreen instanceof GrugScreenGeometry geometry) {
            return geometry.grug$guiLeft();
        }
        return 0.0;
    }

    @Override
    @GrugGenerated("the no-screen arm: a run measures the screen it just opened")
    public double guiWidthForTest() {
        @SuppressWarnings("deprecation")
        net.minecraft.client.Minecraft mc =
                (net.minecraft.client.Minecraft) FabricLoader.getInstance().getGameInstance();
        if (mc.currentScreen instanceof GrugScreenGeometry geometry) {
            return geometry.grug$guiWidth();
        }
        return 0.0;
    }

    @Override
    @GrugGenerated("the no-screen arm: a run measures the screen it just opened")
    public double guiTopForTest() {
        @SuppressWarnings("deprecation")
        net.minecraft.client.Minecraft mc =
                (net.minecraft.client.Minecraft) FabricLoader.getInstance().getGameInstance();
        if (mc.currentScreen instanceof GrugScreenGeometry geometry) {
            return geometry.grug$guiTop();
        }
        return 0.0;
    }

    @Override
    @GrugGenerated("the no-screen arm: a run measures the screen it just opened")
    public double guiHeightForTest() {
        @SuppressWarnings("deprecation")
        net.minecraft.client.Minecraft mc =
                (net.minecraft.client.Minecraft) FabricLoader.getInstance().getGameInstance();
        if (mc.currentScreen instanceof GrugScreenGeometry geometry) {
            return geometry.grug$guiHeight();
        }
        return 0.0;
    }

    @Override
    @GrugGenerated("screenshot/GL integration: failure paths a healthy run cannot enter")
    public void useBlockForTest(Object levelObj, double x, double y, double z) {
        if (refuseWithoutAClient("Test.use_block")) return;
        World world = (World) levelObj;
        @SuppressWarnings("deprecation")
        net.minecraft.client.Minecraft mc =
                (net.minecraft.client.Minecraft) FabricLoader.getInstance().getGameInstance();
        PlayerEntity player = mc.player;
        if (player == null) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr, "Test.use_block: There is no local player to right-click with.");
            return;
        }

        int blockX = (int) Math.floor(x);
        int blockY = (int) Math.floor(y);
        int blockZ = (int) Math.floor(z);

        // World.getBlockId() hands back a block id rather than a Block in this generation.
        int blockId = world.getBlockId(blockX, blockY, blockZ);
        Block block =
                (blockId >= 0 && blockId < Block.BLOCKS.length) ? Block.BLOCKS[blockId] : null;

        if (block instanceof GrugBlock grugBlock) {
            // Go through the client's own right-click, which asks the block's use first and the
            // held
            // item after, so the test drives the same path a real click does and a block that
            // declines leaves the click to the item.
            mc.interactionManager.interactBlock(
                    player, world, player.inventory.getSelectedItem(), blockX, blockY, blockZ, 1);
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
        @SuppressWarnings("deprecation")
        net.minecraft.client.Minecraft mc =
                (net.minecraft.client.Minecraft) FabricLoader.getInstance().getGameInstance();
        PlayerEntity player = mc.player;
        if (player == null) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr, "Test.mining_crack: There is no local player to mine with.");
            return false;
        }

        World world = (World) levelObj;
        int blockX = (int) Math.floor(x);
        int blockY = (int) Math.floor(y);
        int blockZ = (int) Math.floor(z);
        int blockId = world.getBlockId(blockX, blockY, blockZ);
        Block block = (blockId > 0 && blockId < Block.BLOCKS.length) ? Block.BLOCKS[blockId] : null;
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
                        Vec3d.create(blockX + 0.5, blockY + 0.5, blockZ + 0.5));
        GrugBoxRenderer.resetOverlayProbe();
        float previousMiningProgress = mc.worldRenderer.miningProgress;
        mc.worldRenderer.miningProgress = 0.5F;
        mc.worldRenderer.renderMiningProgress(
                player, hit, 0, player.inventory.getSelectedItem(), 0.0F);
        mc.worldRenderer.miningProgress = previousMiningProgress;

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
                Box.create(
                        blockX + minX,
                        blockY + minY,
                        blockZ + minZ,
                        blockX + maxX,
                        blockY + maxY,
                        blockZ + maxZ);
        ArrayList boxes = new ArrayList();
        block.addIntersectingBoundingBox(world, blockX, blockY, blockZ, area, boxes);
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

        // The game itself asks this after the block's state has set its bounding box, which is
        // what the world relies on before it draws the outline.
        block.updateBoundingBox(world, blockX, blockY, blockZ);
        Box box = block.getBoundingBox(world, blockX, blockY, blockZ);
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
        spawnUnlessCiRun((World) levelObj, x, y, z);
    }

    /**
     * Spawns the particle, except in a CI run. This game has no particle setting to turn down, and
     * a particle comes from random numbers the screenshot cannot seed, so a capture with one in it
     * is a different picture on every run. The other loaders' runs set particles to minimal, which
     * drops them the same way.
     */
    @GrugGenerated("the CI run is the only run these paths are measured in, and it drops them")
    private static void spawnUnlessCiRun(World world, double x, double y, double z) {
        if (!"true".equals(System.getenv("GRUG_CI"))) {
            world.addParticle("reddust", x, y, z, 0, 0, 0);
        }
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
            block.randomDisplayTick(world, blockX, blockY, blockZ, new java.util.Random(seed));
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

        // The same call this version's World.raycast makes for the first block its ray reaches, so
        // a click and this answer clip against the same box.
        HitResult hit =
                block.raycast(
                        world,
                        blockX,
                        blockY,
                        blockZ,
                        Vec3d.create(blockX + startX, blockY + startY, blockZ + startZ),
                        Vec3d.create(blockX + endX, blockY + endY, blockZ + endZ));
        return hit != null && hit.blockX == blockX && hit.blockY == blockY && hit.blockZ == blockZ;
    }

    /**
     * The block at a position, or null for air or an id nothing was registered for.
     *
     * <p>The only callers are the three test helpers above, and an id past the table cannot come
     * from a world, so the gate does not measure this lookup.
     */
    @GrugGenerated("test helper: the block lookup the three test helpers above share")
    private static Block blockAt(World world, int x, int y, int z) {
        int blockId = world.getBlockId(x, y, z);
        if (blockId <= 0 || blockId >= Block.BLOCKS.length) {
            return null;
        }
        return Block.BLOCKS[blockId];
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
        File referenceDirectory = new File(InitListener.getActiveGrugModsDir(), referencePath);
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
        @SuppressWarnings("deprecation")
        Minecraft mc = (Minecraft) FabricLoader.getInstance().getGameInstance();
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
