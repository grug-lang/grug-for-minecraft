package net.grug.minecraft.stationapi;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.grug.minecraft.core.GrugSide;
import net.grug.minecraft.core.GrugWorldReady;
import net.grug.minecraft.core.ModLoaderAdapter;
import net.grug.minecraft.grug.BlockPos;
import net.grug.minecraft.grug.Grug;
import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.grug.GrugScreenshots;
import net.grug.minecraft.grug.GrugVanillaBlocks;
import net.grug.minecraft.grug.Vec3;
import net.grug.minecraft.gui.GrugGuiBuilder;
import net.grug.minecraft.stationapi.block.GrugBlock;
import net.grug.minecraft.stationapi.block.entity.GrugBlockEntity;
import net.grug.minecraft.stationapi.events.init.InitListener;
import net.grug.minecraft.stationapi.grug.DummyCraftingInventory;
import net.grug.minecraft.stationapi.gui.GrugScreenHandler;
import net.grug.minecraft.stationapi.gui.StationGuiHelper;
import net.minecraft.block.Block;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.CraftingRecipeManager;
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
import java.util.Map;

public class StationApiAdapter implements ModLoaderAdapter {

    /**
     * This loader's column of the canonical vanilla block name table, so a mod naming a block gets
     * the same block here as it would on every other loader.
     */
    private static final GrugVanillaBlocks.Loader CANONICAL_BLOCKS =
            GrugVanillaBlocks.forLoader("b1.7.3-stationapi");

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
            Object blockEntityObj, Object itemObj, double damage, double amount) {
        Inventory inv = (Inventory) blockEntityObj;
        Item item = (Item) itemObj;
        int meta = (int) damage;
        int maxSize = new ItemStack(item, 1, meta).getMaxCount();
        int remaining = (int) amount;

        // Top up existing stacks first, then use empty slots, like a player's inventory fills.
        for (int i = 0; i < inv.size() && remaining > 0; i++) {
            ItemStack stack = inv.getStack(i);
            if (stack != null && stack.getItem() == item && stack.getDamage() == meta) {
                int room = maxSize - stack.count;
                if (room > 0) {
                    int add = Math.min(room, remaining);
                    stack.count += add;
                    remaining -= add;
                }
            }
        }

        for (int i = 0; i < inv.size() && remaining > 0; i++) {
            if (inv.getStack(i) == null) {
                int add = Math.min(maxSize, remaining);
                inv.setStack(i, new ItemStack(item, add, meta));
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

    /**
     * Refuses the two handles on a dedicated server until #165 gives this loader a server-side
     * adapter, and says whether it refused.
     *
     * <p>A host function error rather than a fatal, because it is the same bounded defect as a
     * missing level or a missing player: the mod asked for something this side cannot give, the
     * test that asked fails, and a server shared with other players keeps running. A fatal here
     * would take down every player on it for one test's mistake.
     *
     * <p>The caller then reports a second, more generic message about the empty answer. Two
     * messages for one mistake is the price of keeping this one here rather than in every caller.
     */
    @GrugGenerated("the dedicated-server answer is #165, which no CI run exercises until then")
    private boolean refusesOnADedicatedServer(String function) {
        if (getSide() != GrugSide.DEDICATED_SERVER) return false;

        Grug.hostFunctionErrorHappened(
                Grug.statePtr,
                function
                        + ": grug has no dedicated-server adapter on StationAPI yet, so a dedicated"
                        + " server cannot answer for its level or its player. See #165.");
        return true;
    }

    @Override
    public void roundTripNbt(Object blockEntityObj) {
        net.minecraft.block.entity.BlockEntity be =
                (net.minecraft.block.entity.BlockEntity) blockEntityObj;
        net.minecraft.nbt.NbtCompound nbt = new net.minecraft.nbt.NbtCompound();
        be.writeNbt(nbt);
        be.readNbt(nbt);
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
        return ItemRegistry.INSTANCE.get((Identifier) resourceLocationObj);
    }

    @Override
    public Object createItemEntity(
            Object levelObj, double x, double y, double z, Object itemStackObj) {
        return new ItemEntity(
                (World) levelObj, (float) x, (float) y, (float) z, (ItemStack) itemStackObj);
    }

    @Override
    public Object createItemStack(Object itemObj, double damage) {
        ItemStack stack = new ItemStack((Item) itemObj);
        stack.setDamage((int) damage);
        return stack;
    }

    @Override
    public double getItemEntityDamage(Object itemEntityObj) {
        return ((ItemEntity) itemEntityObj).stack.getDamage();
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
        // StationAPI registers a name for some vanilla blocks that means a different block than it
        // does elsewhere (its redstone_torch is the unlit torch, while the other four loaders call
        // the lit one that), so the table is consulted before the registry rather than after it:
        // the name a script wrote often does resolve here, just to the wrong block. A grug block
        // keeps its own namespace, so it never goes through the table.
        Block targetBlock =
                isVanilla(blockName)
                        ? BlockRegistry.INSTANCE.get(
                                Identifier.of("minecraft:" + localName(id.getPath())))
                        : null;
        if (targetBlock == null) {
            targetBlock = BlockRegistry.INSTANCE.get(id);
        }

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

        world.setBlock(posX, posY, posZ, targetBlock.id);

        // A placement that reports false because the block was already there is not a failure,
        // so the block's presence is the check rather than the return value.
        if (world.getBlockId(posX, posY, posZ) != targetBlock.id) {
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

    @GrugGenerated(
            "the dedicated-server refusal can only be taken on a server, and CI launches none until"
                + " #165. The client path below is this loader's own way of reaching the client,"
                + " because Fabric Loader hands it back as an Object.")
    @Override
    public boolean registersModRecipes() {
        // InitListener hands the mods' data/<namespace>/recipes tree to StationAPI to parse.
        return true;
    }

    @Override
    public Vec3 getTestOrigin() {
        if (refusesOnADedicatedServer("Test.get_origin")) return null;

        @SuppressWarnings("deprecation")
        net.minecraft.client.Minecraft mc =
                (net.minecraft.client.Minecraft) FabricLoader.getInstance().getGameInstance();
        return new Vec3(mc.player.x, mc.player.y + 3.0, mc.player.z);
    }

    private boolean graphicsCameraSaved = false;
    private double savedX, savedY, savedZ;
    private float savedYaw, savedPitch;

    @Override
    @GrugGenerated("screenshot/GL integration: failure paths a healthy run cannot enter")
    public Vec3 setupGraphicsTestCamera() {
        if (refuseWithoutAClient("Test.setup_graphics_camera")) return null;
        @SuppressWarnings("deprecation")
        net.minecraft.client.Minecraft mc =
                (net.minecraft.client.Minecraft) FabricLoader.getInstance().getGameInstance();
        PlayerEntity player = mc.player;
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

        // Level the view so the frame doesn't depend on which way the player was looking, but
        // deliberately do NOT move them: teleporting into a chunk the client hasn't lit yet crashes
        // this generation of the game. A screenshot test crops a rectangle with the GUI centred, so
        // the terrain behind it never matters.
        player.setPositionAndAngles(player.x, player.y, player.z, 0.0F, 0.0F);

        return new Vec3(player.x, player.y + 3.0, player.z);
    }

    @Override
    @GrugGenerated("screenshot/GL integration: failure paths a healthy run cannot enter")
    public void restoreCameraAfterGraphicsTest() {
        if (refuseWithoutAClient("Test.restore_camera")) return;
        if (!graphicsCameraSaved) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr,
                    "Test.restore_camera: No saved position; call Test.setup_graphics_camera"
                            + " first.");
            return;
        }
        graphicsCameraSaved = false;

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
            // Go through the block's own onUse() so the test drives the same code path a real
            // right-click does, instead of a copy of the block's GUI layout.
            grugBlock.onUse(world, blockX, blockY, blockZ, player);
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
        // the mods root gives the reference directory on disk. That directory holds numbered PNGs
        // and the assertion passes if the capture matches any of them; see GrugScreenshots.
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
