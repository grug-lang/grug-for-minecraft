package net.grug.minecraft.forge;

import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;

import net.grug.minecraft.core.GrugBlockSync;
import net.grug.minecraft.core.GrugClientLight;
import net.grug.minecraft.core.GrugGiveCalls;
import net.grug.minecraft.core.GrugLightSync;
import net.grug.minecraft.core.GrugOperatorStatus;
import net.grug.minecraft.core.GrugRenderSettle;
import net.grug.minecraft.core.GrugSavedDataSync;
import net.grug.minecraft.core.GrugTestBox;
import net.grug.minecraft.core.GrugTestRunner;
import net.grug.minecraft.core.GrugTickLockstep;
import net.grug.minecraft.core.GrugWorldReady;
import net.grug.minecraft.core.ModLoaderAdapter;
import net.grug.minecraft.forge.block.entity.GrugBlockEntity;
import net.grug.minecraft.forge.gui.GrugMenu;
import net.grug.minecraft.forge.mixin.LayerLightSectionStorageAccessor;
import net.grug.minecraft.forge.mixin.LightEngineStorageAccessor;
import net.grug.minecraft.grug.BlockPos;
import net.grug.minecraft.grug.Grug;
import net.grug.minecraft.grug.GrugBlockEntityNbt;
import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.grug.GrugSlotAccess;
import net.grug.minecraft.grug.Vec3;
import net.grug.minecraft.gui.GrugGuiBuilder;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Container;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.TransientCraftingContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.lighting.LayerLightEventListener;
import net.minecraft.world.level.lighting.LayerLightSectionStorage;
import net.minecraft.world.level.lighting.LightEngine;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.server.ServerLifecycleHooks;

import org.slf4j.Logger;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public abstract class ForgeAdapter implements ModLoaderAdapter {
    /**
     * This loader's directory under {@code loaders/}, which names the screenshot references it
     * produces.
     */
    private static final String LOADER_NAME = "1.20.6-forge";

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * The adapter for whichever process this is, called from {@code GrugModLoader}, which runs on
     * both sides: Forge loads a {@code @Mod} class on whichever side the game started as, and
     * grug's {@code [[mods]]} block in {@code mods.toml} has no {@code side} key at all. The two
     * {@code side = "BOTH"} lines in that file are on the dependency blocks, which say how those
     * two dependencies resolve, not how grug loads.
     */
    @GrugGenerated(
            "the choice is which process the game is, which a client run only ever picks one of")
    public static ModLoaderAdapter forCurrentEnvironment() {
        // Two returns and not a conditional expression: the verifier merges a conditional's two
        // types into their common superclass, which loads both classes, and the dedicated server
        // must never load the client one (#1314).
        if (FMLEnvironment.dist == Dist.DEDICATED_SERVER) {
            return new ServerForgeAdapter();
        }
        return new ClientForgeAdapter();
    }

    /**
     * Whether the client holds the chunk at these block coordinates. A dedicated server has no
     * client, so only {@code ClientForgeAdapter} reads the game's client classes to answer it.
     */
    protected abstract boolean clientHasChunk(int x, int z);

    /**
     * The client's own copy of the world. A dedicated server never asks, because it forwards the
     * read to the joined client, so only {@code ClientForgeAdapter} reads the game's client
     * classes.
     */
    @Override
    public abstract Object clientLevel();

    @Override
    public File getGameDirectory() {
        return FMLPaths.GAMEDIR.get().toFile();
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
    @GrugGenerated("client GUI glue: the menu and screen are excluded render glue")
    public void openGui(Object playerObj, Object blockEntityObj, Object guiBuilderObj) {
        if (playerObj instanceof ServerPlayer serverPlayer) {
            if (blockEntityObj instanceof BlockEntity be) {
                GrugGuiBuilder builder = (GrugGuiBuilder) guiBuilderObj;

                serverPlayer.openMenu(
                        new SimpleMenuProvider(
                                (windowId, inv, player) ->
                                        new GrugMenu(
                                                GrugModLoader.GRUG_MENU.get(),
                                                windowId,
                                                inv,
                                                (Container) be,
                                                builder),
                                Component.literal("Grug GUI")),
                        buf -> GrugMenu.writeMenuData(buf, be.getBlockPos(), builder));
            }
        }
    }

    @Override
    public void logInfo(String message) {
        LOGGER.info(message);
    }

    @Override
    public void logError(String message) {
        // A contained mod err, and the sandbox's own report of it: the JNI layer clears whatever a
        // script threw and the script carries on, so this is where a failed script call surfaces.
        LOGGER.error(message);
    }

    @Override
    public void setEntityDeltaMovement(Object entityObj, double dx, double dy, double dz) {
        ((Entity) entityObj).setDeltaMovement(dx, dy, dz);
    }

    @Override
    public Vec3 getEntityDeltaMovement(Object entityObj) {
        net.minecraft.world.phys.Vec3 movement = ((Entity) entityObj).getDeltaMovement();
        return new Vec3(movement.x, movement.y, movement.z);
    }

    @Override
    public void spawnEntity(Object levelObj, Object entityObj) {
        ((Level) levelObj).addFreshEntity((Entity) entityObj);
    }

    @Override
    public void consumeCraftingIngredients(Object blockEntityObj, double startSlot) {
        Container inv = (Container) blockEntityObj;
        for (int i = 0; i < 9; i++) {
            int slot = (int) startSlot + i;
            ItemStack stack = inv.getItem(slot);
            if (!stack.isEmpty()) {
                inv.removeItem(slot, 1);
                applyCraftingRemaining(inv, slot, stack);
            }
        }
    }

    @GrugGenerated("crafting remaining item: no item in this version carries one")
    private static void applyCraftingRemaining(Container inv, int slot, ItemStack stack) {
        if (stack.getItem().hasCraftingRemainingItem()) {
            inv.setItem(slot, new ItemStack(stack.getItem().getCraftingRemainingItem()));
        }
    }

    @Override
    public double countItemInInventory(Object blockEntityObj, Object itemObj, double damage) {
        Container inv = (Container) blockEntityObj;
        Item item = (Item) itemObj;
        int total = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            // 1.20.6 has removed traditional damage values for components, but assuming
            // direct comparison here.
            if (!stack.isEmpty() && stack.is(item) && stack.getDamageValue() == (int) damage) {
                total += stack.getCount();
            }
        }
        return total;
    }

    @Override
    public void dropInventory(Object levelObj, double x, double y, double z) {
        Level world = (Level) levelObj;
        BlockEntity be =
                world.getBlockEntity(
                        new net.minecraft.core.BlockPos(
                                (int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z)));
        Container inv = (Container) be;
        Containers.dropContents(world, be.getBlockPos(), inv);
        inv.clearContent();
    }

    @Override
    public double extractItemFromInventory(
            Object blockEntityObj, Object itemObj, double damage, double amount) {
        Container inv = (Container) blockEntityObj;
        Item item = (Item) itemObj;
        int remainingToExtract = (int) amount;
        for (int i = 0; i < inv.getContainerSize() && remainingToExtract > 0; i++) {
            if (!GrugSlotAccess.isExtractable(blockEntityObj, i)) continue;
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && stack.is(item) && stack.getDamageValue() == (int) damage) {
                int extractFromSlot = Math.min(stack.getCount(), remainingToExtract);
                inv.removeItem(i, extractFromSlot);
                remainingToExtract -= extractFromSlot;
            }
        }
        return amount - remainingToExtract;
    }

    @Override
    public double insertItemIntoInventory(
            Object blockEntityObj, Object itemObj, double damage, double amount, boolean insert) {
        Container inv = (Container) blockEntityObj;
        Item item = (Item) itemObj;
        int meta = (int) damage;
        int maxSize = new ItemStack(item).getMaxStackSize();
        int remaining = (int) amount;

        // Top up existing stacks first, then use empty slots, like a player's inventory fills. A
        // block entity that declares a rule only has its insertable slots filled, which for an
        // inventory declaring none is all of them. With insert false the count that would fit is
        // answered and nothing is changed, which is how a caller asks whether the inventory has
        // room.
        for (int i = 0; i < inv.getContainerSize() && remaining > 0; i++) {
            if (!GrugSlotAccess.isInsertable(blockEntityObj, i)) continue;
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && stack.is(item) && stack.getDamageValue() == meta) {
                int room = maxSize - stack.getCount();
                if (room > 0) {
                    int add = Math.min(room, remaining);
                    if (insert) {
                        stack.grow(add);
                    }
                    remaining -= add;
                }
            }
        }

        for (int i = 0; i < inv.getContainerSize() && remaining > 0; i++) {
            if (!GrugSlotAccess.isInsertable(blockEntityObj, i)) continue;
            if (inv.getItem(i).isEmpty()) {
                int add = Math.min(maxSize, remaining);
                if (insert) {
                    ItemStack stack = new ItemStack(item, add);
                    stack.setDamageValue(meta);
                    inv.setItem(i, stack);
                }
                remaining -= add;
            }
        }

        return amount - remaining;
    }

    @Override
    public double takeItemFromSlot(Object blockEntityObj, double slot, double amount) {
        Container inv = (Container) blockEntityObj;
        ItemStack removed = inv.removeItem((int) slot, (int) amount);
        return !removed.isEmpty() ? removed.getCount() : 0;
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
        return ((ItemStack) stackObj).getDamageValue();
    }

    @Override
    public double getStackCount(Object stackObj) {
        return ((ItemStack) stackObj).getCount();
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
        Container inv = (Container) blockEntityObj;
        Item item = (Item) itemObj;
        int meta = (int) damage;
        int maxSize = new ItemStack(item).getMaxStackSize();
        ItemStack stack = inv.getItem((int) slot);
        int room;
        if (stack.isEmpty()) {
            room = maxSize;
        } else if (stack.is(item) && stack.getDamageValue() == meta) {
            room = maxSize - stack.getCount();
        } else {
            room = 0;
        }
        int add = Math.min(Math.max(room, 0), (int) amount);
        if (commit && add > 0) {
            if (stack.isEmpty()) {
                ItemStack created = new ItemStack(item, add);
                created.setDamageValue(meta);
                inv.setItem((int) slot, created);
            } else {
                stack.grow(add);
            }
        }
        return add;
    }

    @Override
    public double takeCraftingResult(Object blockEntityObj, double amount) {
        if (blockEntityObj instanceof GrugBlockEntity gbe) {
            ItemStack removed = gbe.takeResultStack((int) amount);
            if (!removed.isEmpty()) {
                gbe.notifyOutputTaken(removed.getCount());
                return removed.getCount();
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
        return ((Level) levelObj)
                .getBlockEntity(
                        new net.minecraft.core.BlockPos(
                                (int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z)));
    }

    @Override
    public Object getBlockEntityLevel(Object blockEntityObj) {
        return ((BlockEntity) blockEntityObj).getLevel();
    }

    /**
     * The running server's world when there is one, and the client's own copy otherwise.
     *
     * <p>In singleplayer the two are not the same object: the client holds a {@code ClientLevel}
     * that mirrors the {@code ServerLevel}, and a block placed through the client is placed in the
     * mirror. A fixture has to go into the world that owns it, so the server's answer wins and the
     * client's is only there for a client connected to somebody else, where there is no local
     * server at all.
     */
    @GrugGenerated(
            "singleplayer versus a connected client: which world owns the block is loader plumbing")
    @Override
    public Object getLevel() {
        if (ServerLifecycleHooks.getCurrentServer() != null) {
            return ServerLifecycleHooks.getCurrentServer().overworld();
        }
        return clientLevel();
    }

    @Override
    @GrugGenerated("no player: the run has no tests to build a room for until someone has joined")
    public void buildTestBox(int radius) {
        // The room build is the first thing the runner does for a test, and it dirties the
        // renderer, so any settle answer from before it describes frames this test must not
        // capture. See GrugRenderSettle.reset.
        RENDER_SETTLE.reset();
        Player player = (Player) testPlayer();
        if (player == null) return;
        GrugTestBox.build(
                this, getLevel(), player.getX(), player.getY(), player.getZ(), radius, false);
    }

    @Override
    @GrugGenerated("no player: the run has no tests to sweep after until someone has joined")
    public void sweepTestItems() {
        Player player = (Player) testPlayer();
        if (player == null) return;
        GrugTestBox.sweepItems(this, getLevel(), player.getX(), player.getY(), player.getZ());
    }

    @Override
    public void roundTripNbt(Object blockEntityObj) {
        BlockEntity be = (BlockEntity) blockEntityObj;
        Level level = be.getLevel();
        CompoundTag tag = be.saveWithoutMetadata(level.registryAccess());
        be.loadWithComponents(tag, level.registryAccess());
    }

    private final GrugBlockEntityNbtAccess blockEntityNbt = new GrugBlockEntityNbtAccess();

    @Override
    public GrugBlockEntityNbt.Access blockEntityNbt() {
        return blockEntityNbt;
    }

    /**
     * The ticks per second of the game's clock in real time, which vanilla's tick rate defaults to.
     */
    static final float REAL_TIME_TICK_RATE = 20.0F;

    /**
     * The tick rate of the integrated server while the clock runs fast: high enough that the
     * schedule is never what holds a tick back, because the lockstep with the client is. See {@link
     * GrugTickLockstep}.
     */
    static final float FAST_TICK_RATE = REAL_TIME_TICK_RATE * GrugTestRunner.MAX_GAME_SPEED;

    @Override
    public double maxGameSpeed() {
        return CLIENT_TICKS_PER_FRAME;
    }

    @Override
    public void tickBlockEntity(Object blockEntityObj) {
        ((GrugBlockEntity) blockEntityObj).tick();
    }

    /**
     * Runs the server level's own tick the given number of times, at the point of the call.
     *
     * <p>The test's tick is a task the server runs from {@code waitUntilNextTick}, between its own
     * ticks, so the advance happens between server ticks rather than inside one. {@code
     * ServerLevel.tick} is that tick: the world border, weather, the level's clock, scheduled block
     * and fluid ticks, the chunk source with its random block ticks and natural spawning, block
     * events, entity ticks and block entity ticks. The server's own bookkeeping around it does not
     * run, which is the point: no {@code tickCount}, no tick rate manager, no autosave, no {@code
     * LevelTickEvent} or {@code ServerTickEvent} listener and no packet flush per tick.
     *
     * <p>{@code hasTimeLeft} answers true so that the chunk source drains its whole tick instead of
     * stopping at a deadline that the caller is the one holding. The answer is the number of ticks
     * that ran, so a test can tell an advance from a call that did nothing. See #753 and #780.
     */
    @GrugGenerated(
            "which process holds a level: a test's level is the server's overworld whenever a"
                + " server runs, which every run does, and a client's own copy otherwise, which no"
                + " test reaches because nothing here runs a client against another server")
    @Override
    public int tickLevel(Object levelObj, int ticks) {
        if (!(levelObj instanceof ServerLevel serverLevel)) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr,
                    "Test.tick_level: Only a server level has a tick this process can run, and this"
                            + " is not one.");
            return 0;
        }
        return advanceLevelTicks(serverLevel, ticks);
    }

    /**
     * The advance itself, which is what a test pays for instead of a frame per tick, answering how
     * many ticks it ran.
     *
     * <p>No test can see the order inside one advance: the test's own tick observes the state after
     * every tick it asked for, and grug has no way to schedule a block update that comes due at a
     * chosen tick of the advance, so the order is checked against the game's own tick that this
     * method calls, not by a test. What a test does see is that the level moved at all, which is
     * {@code mods/coverage/tests/tick_level-Test.grug}'s clock and the wooden pipe test's item.
     */
    private static int advanceLevelTicks(ServerLevel level, int ticks) {
        for (int tick = 0; tick < ticks; tick++) {
            level.tick(() -> true);
        }
        return ticks;
    }

    @Override
    public void redraw(Object blockEntityObj) {
        BlockEntity be = (BlockEntity) blockEntityObj;
        // The client learns of the change from a packet it has not read yet, so no frame has seen
        // it and the frames drawn before this call say nothing about it: the capture waits for two
        // after it. Up to 10 ticks run for a frame, so the test's own wait cannot stand in for it.
        RENDER_SETTLE.noteBusy();
        // The transport is the side that owns the block entity: it sends the saved data to the
        // clients holding it, and their GrugBlockEntity.onDataPacket marks their own section and
        // builds the mesh again. A client's own copy of a redrawn block entity has no transport
        // that sends, so its rebuild is the local mark below; a client never sends a packet. One
        // rebuild per side, never two. The side decision lives in core, so a core unit test covers
        // both answers, and redraw_custom_render draws the client's rebuilt plate.
        GrugSavedDataSync.redrawn(be);
        GrugSavedDataSync.onClientLevel(
                be.getLevel().isClientSide(),
                () ->
                        be.getLevel()
                                .sendBlockUpdated(
                                        be.getBlockPos(),
                                        be.getBlockState(),
                                        be.getBlockState(),
                                        net.minecraft.world.level.block.Block.UPDATE_CLIENTS));
    }

    @Override
    public BlockPos getBlockPosOfBlockEntity(Object blockEntityObj) {
        net.minecraft.core.BlockPos pos = ((BlockEntity) blockEntityObj).getBlockPos();
        return new BlockPos(pos.getX(), pos.getY(), pos.getZ());
    }

    @Override
    public double getInventorySize(Object blockEntityObj) {
        return ((Container) blockEntityObj).getContainerSize();
    }

    @Override
    public double getItemCountInSlot(Object blockEntityObj, double slot) {
        ItemStack stack = ((Container) blockEntityObj).getItem((int) slot);
        return !stack.isEmpty() ? stack.getCount() : 0;
    }

    @Override
    public double getItemDamageInSlot(Object blockEntityObj, double slot) {
        ItemStack stack = ((Container) blockEntityObj).getItem((int) slot);
        return !stack.isEmpty() ? stack.getDamageValue() : 0;
    }

    @Override
    public Object getItemInSlot(Object blockEntityObj, double slot) {
        ItemStack stack = ((Container) blockEntityObj).getItem((int) slot);
        return !stack.isEmpty() ? stack.getItem() : null;
    }

    @Override
    public Object getItemFromRegistry(Object resourceLocationObj) {
        ResourceLocation id = (ResourceLocation) resourceLocationObj;

        // Forge answers a key it does not have with air, which would make a typo look like a real
        // item and differ from the other four loaders, so ask whether the key exists first. A real
        // minecraft:air still resolves.
        return ForgeRegistries.ITEMS.containsKey(id) ? ForgeRegistries.ITEMS.getValue(id) : null;
    }

    /**
     * Every item this loader lists, in the order item_at answers it: the minecraft namespace first,
     * then every other namespace and the path inside it alphabetically.
     *
     * <p>JEI lists by creative tab and then by mod name, so the order differs from the reference
     * and the port records that as a deviation. Ordering by key is stable across runs and does not
     * move when a mod is added, which a golden of the grid needs.
     *
     * <p>Built once and kept: the registry does not change while the game runs, and rebuilding and
     * sorting about a thousand items on every drawn frame would cost more than the drawing does.
     */
    private List<Item> itemsByNameCache;

    private List<Item> itemsByName() {
        if (itemsByNameCache == null) {
            List<Item> items = new ArrayList<>();
            for (Item item : ForgeRegistries.ITEMS) {
                // Air is an item in the registry but not an ingredient: JEI's list comes from the
                // creative tabs, which have no air, and an empty stack cannot be drawn or given.
                if (item == Items.AIR) continue;
                items.add(item);
            }
            items.sort(
                    (a, b) -> {
                        ResourceLocation keyA = ForgeRegistries.ITEMS.getKey(a);
                        ResourceLocation keyB = ForgeRegistries.ITEMS.getKey(b);
                        boolean vanillaA = "minecraft".equals(keyA.getNamespace());
                        boolean vanillaB = "minecraft".equals(keyB.getNamespace());
                        int vanillaOrder = Boolean.compare(vanillaB, vanillaA);
                        if (vanillaOrder != 0) return vanillaOrder;
                        return keyA.compareTo(keyB);
                    });
            itemsByNameCache = items;
        }
        return itemsByNameCache;
    }

    @Override
    public int itemCount() {
        return itemsByName().size();
    }

    @Override
    @GrugGenerated(
            "the out-of-range guard: the host function checks the range first, so a run"
                    + " never reaches this arm")
    public Object itemByIndex(int index) {
        List<Item> items = itemsByName();
        return index >= 0 && index < items.size() ? items.get(index) : null;
    }

    @Override
    @GrugGenerated(
            "the joined-client give (#1099): only the multiplayer job launches a client joined to a"
                    + " server, and the server that applies its give, so a run the coverage"
                    + " gate counts never names an item and gives it to its own player")
    public String itemName(Object itemObj) {
        ResourceLocation key = ForgeRegistries.ITEMS.getKey((Item) itemObj);
        return key == null ? null : key.toString();
    }

    @Override
    public int getItemMaxStack(Object itemObj) {
        return new ItemStack((Item) itemObj).getMaxStackSize();
    }

    @Override
    public void giveItemCountToPlayer(Object playerObj, Object itemObj, int count) {
        giveItemToPlayer((Player) playerObj, (Item) itemObj, count);
    }

    /**
     * What this loader answers for the owner of a singleplayer world (#1331). The game's own rule
     * makes the owner an operator only while the world allows cheats, which a world that was made
     * without them never does, and holds no state a script could revoke and read back, so the
     * answer is the operator list's, or the owner while no script has revoked it.
     */
    private final GrugOperatorStatus operatorStatus = new GrugOperatorStatus();

    /**
     * The server that holds the players: the dedicated server, or the integrated one under a
     * singleplayer client. A client joined to a remote server has none, and the host functions
     * never ask it (see {@code GrugOperatorCalls}), so reaching this without one is a bug here.
     */
    @GrugGenerated("the invariant arm: the host functions refuse a joined client before asking")
    private static MinecraftServer serverHoldingPlayers() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            throw Grug.fatal("An operator was asked for with no server that holds the players.");
        }
        return server;
    }

    /**
     * Whether the operator list holds the player, or the player owns a singleplayer world and no
     * script has removed that. The list is the game's own {@code PlayerList.getOps}, read by the
     * profile, which both the client's player and the server's carry.
     */
    @Override
    public boolean isOperator(Object playerObj) {
        MinecraftServer server = serverHoldingPlayers();
        GameProfile profile = ((Player) playerObj).getGameProfile();
        return operatorStatus.isOperator(
                server.isSingleplayerOwner(profile),
                server.getPlayerList().getOps().contains(profile));
    }

    /** Adds the player to the operator list, as the game's {@code /op} does. */
    @Override
    public void makeOperator(Object playerObj) {
        serverHoldingPlayers().getPlayerList().op(((Player) playerObj).getGameProfile());
        operatorStatus.granted();
    }

    /** Removes the player from the operator list, as the game's {@code /deop} does. */
    @Override
    public void removeOperator(Object playerObj) {
        serverHoldingPlayers().getPlayerList().deop(((Player) playerObj).getGameProfile());
        operatorStatus.revoked();
    }

    /**
     * Counts the runner's resets between tests, which is how a give the game queued for its server
     * thread by the test that just ended is dropped instead of landing in the next test's inventory
     * (#1144). {@link GrugGiveCalls#applyQueuedGiveIfCurrent} compares it with the count a give
     * captured.
     */
    private volatile long giveResets;

    @Override
    public void forgetQueuedGivesForTest() {
        giveResets++;
    }

    @GrugGenerated(
            "the no-server and server-thread arms: a run clicks from the client of an"
                    + " integrated server, so the give is always queued")
    private void giveItemToPlayer(Player player, Item item, int count) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            // A dedicated server or a client with no world: there is no inventory to add to, and a
            // report here would fire on every click of a screen that is not in a real game.
            return;
        }
        // An overlay's click arrives on the client thread, but the inventory is the server's, so
        // the
        // give is queued for the server thread rather than mutated from the client. It is not
        // waited
        // for: the server thread can be blocked waiting on the client, and the waiting would be a
        // deadlock. The give lands on that thread's next tick, exactly as JEI's packet does.
        if (server.isSameThread()) {
            giveOnServerThread(player, item, count);
            return;
        }
        // The reset that starts the next test drops a give still queued here: it belongs to no
        // test's inventory once one has run since it was queued (#1144).
        long resets = giveResets;
        server.execute(
                () ->
                        GrugGiveCalls.applyQueuedGiveIfCurrent(
                                resets, giveResets, () -> giveOnServerThread(player, item, count)));
    }

    @GrugGenerated(
            "a full inventory and a missing dropped entity: a run has room for the one item"
                    + " or stack it gives")
    private void giveOnServerThread(Player playerObj, Item item, int count) {
        Player player = serverSideOf(playerObj);
        ItemStack stack = new ItemStack(item, count);

        // add() takes what fits and leaves the rest in the stack, so a full inventory drops exactly
        // the remainder at the player's feet rather than losing it, which is what JEI's own give
        // does when its inventory add does not empty the stack.
        player.getInventory().add(stack);
        if (!stack.isEmpty()) {
            ItemEntity dropped = player.drop(stack, false);
            if (dropped != null) {
                dropped.setNoPickUpDelay();
                dropped.setTarget(player.getUUID());
            }
        }
        player.inventoryMenu.broadcastChanges();
    }

    @Override
    public Object createItemEntity(
            Object levelObj, double x, double y, double z, Object itemStackObj) {
        return new ItemEntity((Level) levelObj, x, y, z, (ItemStack) itemStackObj);
    }

    @Override
    public Object createItemStack(Object itemObj, double damage, double count) {
        ItemStack stack = new ItemStack((Item) itemObj, (int) count);
        stack.setDamageValue((int) damage);
        return stack;
    }

    @Override
    public int maxItemStackCount() {
        return Integer.MAX_VALUE;
    }

    @Override
    public double getItemEntityDamage(Object itemEntityObj) {
        return ((ItemEntity) itemEntityObj).getItem().getDamageValue();
    }

    @Override
    public double getItemEntityCount(Object itemEntityObj) {
        return ((ItemEntity) itemEntityObj).getItem().getCount();
    }

    @Override
    public double getItemEntityPickupDelay(Object itemEntityObj) {
        // The field is private and has no getter; the saved data holds it as a short.
        return ((ItemEntity) itemEntityObj)
                .saveWithoutId(new net.minecraft.nbt.CompoundTag())
                .getShort("PickupDelay");
    }

    @Override
    public void setItemEntityPickupDelay(Object itemEntityObj, int ticks) {
        ((ItemEntity) itemEntityObj).setPickUpDelay(ticks);
    }

    @Override
    public void clearItemEntities(
            Object levelObj, double x1, double y1, double z1, double x2, double y2, double z2) {
        Level world = (Level) levelObj;
        net.minecraft.world.phys.AABB box =
                new net.minecraft.world.phys.AABB(x1, y1, z1, x2, y2, z2);
        for (ItemEntity item : world.getEntitiesOfClass(ItemEntity.class, box)) {
            item.discard();
        }
    }

    @Override
    public Object findItemEntity(Object levelObj, double x, double y, double z, double radius) {
        Level world = (Level) levelObj;
        net.minecraft.world.phys.AABB box =
                new net.minecraft.world.phys.AABB(
                        x - radius, y - radius, z - radius, x + radius, y + radius, z + radius);
        java.util.List<ItemEntity> entities = world.getEntitiesOfClass(ItemEntity.class, box);
        return entities.isEmpty() ? null : entities.get(0);
    }

    @Override
    public Object createResourceLocation(String string) {
        return new ResourceLocation(string);
    }

    @Override
    public void setItemCountInSlot(Object blockEntityObj, double slot, double count) {
        Container inv = (Container) blockEntityObj;
        ItemStack stack = inv.getItem((int) slot);
        if (!stack.isEmpty()) {
            if (count <= 0) {
                inv.setItem((int) slot, ItemStack.EMPTY);
            } else {
                stack.setCount((int) count);
            }
        }
    }

    @Override
    public void setItemDamageInSlot(Object blockEntityObj, double slot, double damage) {
        ItemStack stack = ((Container) blockEntityObj).getItem((int) slot);
        if (!stack.isEmpty()) {
            stack.setDamageValue((int) damage);
        }
    }

    @Override
    public void setItemInSlot(Object blockEntityObj, double slot, Object itemObj, double count) {
        Container inv = (Container) blockEntityObj;
        inv.setItem((int) slot, new ItemStack((Item) itemObj, (int) count));
    }

    @GrugGenerated("recipe output: crafting glue that needs the level's recipe manager")
    @Override
    public void updateRecipeOutput(Object blockEntityObj, double startSlot) {
        if (!(blockEntityObj instanceof GrugBlockEntity gbe)
                || !(blockEntityObj instanceof BlockEntity be)
                || be.getLevel() == null
                || !(be instanceof Container inv)) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr,
                    "update_recipe_output: "
                            + blockEntityObj.getClass().getName()
                            + " has no crafting result container.");
            return;
        }

        Level level = be.getLevel();
        TransientCraftingContainer craftingContainer =
                new TransientCraftingContainer(
                        new AbstractContainerMenu(null, -1) {
                            @GrugGenerated("crafting menu placeholder")
                            @Override
                            public ItemStack quickMoveStack(Player player, int index) {
                                return ItemStack.EMPTY;
                            }

                            @GrugGenerated("crafting menu placeholder")
                            @Override
                            public boolean stillValid(Player player) {
                                return false;
                            }
                        },
                        3,
                        3);

        int start = (int) startSlot;
        for (int i = 0; i < 9; i++) {
            craftingContainer.setItem(i, inv.getItem(start + i));
        }

        Optional<RecipeHolder<CraftingRecipe>> recipe =
                level.getRecipeManager()
                        .getRecipeFor(RecipeType.CRAFTING, craftingContainer, level);

        if (recipe.isPresent()) {
            ItemStack result =
                    recipe.get().value().assemble(craftingContainer, level.registryAccess());
            gbe.setResultStack(result);
        } else {
            gbe.setResultStack(ItemStack.EMPTY);
        }
    }

    @Override
    public void placeBlock(Object levelObj, double x, double y, double z, String blockName) {
        Level world = (Level) levelObj;
        ResourceLocation id =
                new ResourceLocation(
                        blockName.contains(":") ? blockName : "minecraft:" + blockName);
        // air cannot go through resolveBlock: Forge's registry answers AIR for a name it does not
        // know, so air and "not a block" are the same answer there. Placing air is how a test
        // removes a block, so it skips the lookup and is placed like any other block.
        Block targetBlock = id.getPath().equals("air") ? Blocks.AIR : resolveBlock(id);

        if (targetBlock == null) {
            // A name that resolves nowhere is a defect in the mod, not a block to skip: it would
            // leave the mod building against a block it never got, which is the cross loader
            // mismatch the canonical name table exists to remove. The host function error is the
            // report, so there is no second log line.
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr, "place_block: Could not resolve block " + blockName);
            return;
        }

        net.minecraft.core.BlockPos pos =
                new net.minecraft.core.BlockPos(
                        (int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));

        world.setBlockAndUpdate(pos, targetBlock.defaultBlockState());
        // The client learns of the block from a packet it has not read yet, so the frames drawn
        // before this call say nothing about it. See redraw.
        RENDER_SETTLE.noteBusy();

        // A placement that reports false because the block was already there is not a failure,
        // so the block's presence is the check rather than the return value. A y outside the
        // build height is refused, and a position in a chunk the client has not received is
        // written to the server's world only: the client never gets that chunk, so it shows
        // nothing there and the settle wait does not wait for it. A write the game accepts and
        // does not leave in place is reported the same way: the caller asked for a block that is
        // not there afterwards. See #151.
        if (world.getBlockState(pos).getBlock() != targetBlock) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr,
                    "place_block: the game did not put "
                            + blockName
                            + " at "
                            + pos.getX()
                            + ", "
                            + pos.getY()
                            + ", "
                            + pos.getZ()
                            + ". A y outside this version's world height is refused, and a"
                            + " position in a chunk the client has not received is written to the"
                            + " server's world alone, which the client never shows.");
            return;
        }
        // The client's copy of a chunk it has not received does not exist: this version answers
        // void air there and never sends the chunk while the player is nowhere near it, so the
        // settle wait would wait for a block the client can never show. The chunk packet that does
        // arrive carries the block states it was sent with, and the frames a chunk load draws are
        // what the frame rule already waits for, so only a position in a chunk the client already
        // holds has an update of its own to wait for. See #804.
        if (!clientHasChunk(pos.getX(), pos.getZ())) {
            return;
        }
        // The client learns of the block from a packet it has not read yet, so the settle wait has
        // to ask the client's own copy rather than trust the frames that passed. See #804.
        BLOCK_SYNC.owe(pos.getX(), pos.getY(), pos.getZ(), blockNameAt(world, pos));
    }

    @Override
    public long getWorldTime(Object levelObj) {
        return ((Level) levelObj).getDayTime();
    }

    @Override
    public int nextRandomInt(Object levelObj, int bound) {
        return ((Level) levelObj).random.nextInt(bound);
    }

    @Override
    public double nextRandomFloat(Object levelObj) {
        return ((Level) levelObj).random.nextFloat();
    }

    @Override
    public double nextRandomGaussian(Object levelObj) {
        return ((Level) levelObj).random.nextGaussian();
    }

    @Override
    public void explode(Object levelObj, double x, double y, double z, double power) {
        ((Level) levelObj).explode(null, x, y, z, (float) power, Level.ExplosionInteraction.BLOCK);
    }

    /**
     * The player a right-click is answered for. In single player the block's use runs on the
     * integrated server, which reads the server's copy of the player's hand, so a test that puts
     * something in the client player's hand has to put it in the server's copy to be seen.
     */
    @GrugGenerated("singleplayer versus a connected client: which hand the use reads is plumbing")
    private static Player serverSideOf(Player player) {
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server == null || !player.level().isClientSide) return player;
        ServerPlayer serverPlayer = server.getPlayerList().getPlayer(player.getUUID());
        return serverPlayer != null ? serverPlayer : player;
    }

    @Override
    public Object getHeldItem(Object playerObj) {
        ItemStack stack = serverSideOf((Player) playerObj).getMainHandItem();
        return !stack.isEmpty() ? stack.getItem() : null;
    }

    @Override
    public void setHeldItem(Object playerObj, Object itemObj) {
        serverSideOf((Player) playerObj)
                .setItemInHand(
                        InteractionHand.MAIN_HAND,
                        itemObj == null ? ItemStack.EMPTY : new ItemStack((Item) itemObj));
    }

    @Override
    public void clearInventoryOf(Object playerObj) {
        net.minecraft.world.entity.player.Inventory inventory =
                serverSideOf((Player) playerObj).getInventory();
        inventory.clearContent();
        inventory.selected = 0;
    }

    @Override
    public double getHeldCount(Object playerObj) {
        return serverSideOf((Player) playerObj).getMainHandItem().getCount();
    }

    @Override
    public boolean hasSound(String path) {
        return BuiltInRegistries.SOUND_EVENT.containsKey(new ResourceLocation("minecraft", path));
    }

    @Override
    public boolean hasNeighborSignal(Object levelObj, double x, double y, double z) {
        Level world = (Level) levelObj;
        return world.hasNeighborSignal(
                new net.minecraft.core.BlockPos(
                        (int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z)));
    }

    public String getBlock(Object levelObj, double x, double y, double z) {
        Level world = (Level) levelObj;
        net.minecraft.core.BlockPos pos =
                new net.minecraft.core.BlockPos(
                        (int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
        return blockNameAt(world, pos);
    }

    /** The name the client or the server holds at a position, as both spell it in a registry. */
    static String blockNameAt(Level world, net.minecraft.core.BlockPos pos) {
        Block block = world.getBlockState(pos).getBlock();
        return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block).toString();
    }

    static String blockNameAt(Level world, int x, int y, int z) {
        return blockNameAt(world, new net.minecraft.core.BlockPos(x, y, z));
    }

    @Override
    public boolean isAir(Object levelObj, double x, double y, double z) {
        Level world = (Level) levelObj;
        net.minecraft.core.BlockPos pos =
                new net.minecraft.core.BlockPos(
                        (int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
        return world.getBlockState(pos).isAir();
    }

    @Override
    public double countItemEntities(Object levelObj, double x, double y, double z, double radius) {
        Level world = (Level) levelObj;
        net.minecraft.world.phys.AABB box =
                new net.minecraft.world.phys.AABB(
                        x - radius, y - radius, z - radius, x + radius, y + radius, z + radius);
        return world.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, box)
                .size();
    }

    @Override
    public void notifyNeighbors(Object levelObj, double x, double y, double z) {
        Level world = (Level) levelObj;
        net.minecraft.core.BlockPos pos =
                new net.minecraft.core.BlockPos(
                        (int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
        world.updateNeighborsAt(pos, world.getBlockState(pos).getBlock());
    }

    @GrugGenerated("block resolution: Forge returns AIR rather than null for unknown blocks")
    private Block resolveBlock(ResourceLocation id) {
        Block targetBlock = ForgeRegistries.BLOCKS.getValue(id);
        return (targetBlock != null && targetBlock != Blocks.AIR) ? targetBlock : null;
    }

    @Override
    public boolean isWorldReady(Object playerObj) {
        Player player = (Player) playerObj;
        Object level = getLevel();

        return GrugWorldReady.isReady(
                (x, y, z) -> isAir(level, x, y, z), player.getX(), player.getY(), player.getZ());
    }

    @Override
    public boolean separatesClientLight() {
        return true;
    }

    /**
     * This client's own copy of the light of the section the position falls in, which is the copy
     * its frame pass bakes the room from (#1207).
     *
     * <p>{@link GrugClientLight#NOT_RECEIVED} for a position in a chunk column this client was
     * never sent: it holds no copy of that section at all, which is not the same answer as a
     * section whose light is still zero. A section the client was never sent cannot hold the
     * world's light, and a fingerprint of 0 would agree with a world section the light engine has
     * not reached yet, so the comparison would call a room the client has never seen settled
     * (#1242). The light a client holds for a column it has is the section's published layer, which
     * is the copy the server thread can read (#1207).
     */
    @GrugGenerated(
            "client light: a client process with no level has no section to read, which a CI"
                    + " client run never is")
    @Override
    public long clientLightFingerprint(double x, double y, double z) {
        Object levelObj = clientLevel();
        if (levelObj == null) {
            return GrugClientLight.CANNOT_VERIFY;
        }
        Level level = (Level) levelObj;
        if (!level.getChunkSource()
                .hasChunk(((int) Math.floor(x)) >> 4, ((int) Math.floor(z)) >> 4)) {
            return GrugClientLight.NOT_RECEIVED;
        }
        return sectionLightFingerprint(level, x, y, z);
    }

    /**
     * Whether this client's copy holds the light the simulated world has in the section the
     * position falls in (#1207). A dedicated server overrides this to ask its joined client,
     * because there is no client here and the inherited answer would read a null level.
     *
     * <p>The section's bytes, not the position's light. A test reads the client's copy from the
     * server thread on the leg where both run in one process, and the position read goes through
     * the light engine's working store, which the client thread does not publish: its update is
     * preceded by no memory barrier, and its two-entry section cache can hand the server thread the
     * layer from before the change for as long as the test waits. The client thread does publish
     * the finished section through {@code visibleSectionData}, which is volatile, and that is what
     * {@link #blockLightLayer} reads and what the settle already compares. So the section bytes are
     * the copy the server thread can see arrive, and the copy the client draws from once it has.
     *
     * <p>The world's light is sampled after the settled check and again after the client's copy is
     * read, so a light batch that the engine had already scheduled and that publishes between the
     * samples makes them differ rather than passing the pre-batch light for the room's, and the
     * engine is asked once more after both. A client answer of {@link GrugClientLight#NOT_RECEIVED}
     * is false because a column the client was never sent cannot hold the world's light, while
     * {@link GrugClientLight#CANNOT_VERIFY} is true. See {@link GrugLightSync#lightApplied}, #1240
     * and #1248.
     */
    @GrugGenerated(
            "client light: the simulated world is always there on a CI client run, so the arm"
                    + " with no level has no test")
    @Override
    public boolean lightApplied(double x, double y, double z) {
        long simulated = sectionLightFingerprint(getLevel(), x, y, z);
        if (!lightEngineSettled(getLevel())) {
            return false;
        }
        long client = clientLightFingerprint(x, y, z);
        long settled = sectionLightFingerprint(getLevel(), x, y, z);
        boolean idle = lightEngineSettled(getLevel());
        return GrugLightSync.lightApplied(idle, simulated, settled, client);
    }

    /**
     * The light of the section a position falls in, as {@link GrugLightSync#lightFingerprint}, or
     * -1 when there is no level to read it from (#1207).
     */
    @GrugGenerated("client light: a level that does not exist has no section to read")
    static long sectionLightFingerprint(Object levelObj, double x, double y, double z) {
        if (levelObj == null) {
            return -1;
        }
        return GrugLightSync.lightFingerprint(
                blockLightLayer(
                        levelObj,
                        ((int) Math.floor(x)) >> 4,
                        ((int) Math.floor(y)) >> 4,
                        ((int) Math.floor(z)) >> 4));
    }

    /**
     * Whether the light engine of a level this server simulates has no light work left in its own
     * queues, so the light it holds at every block is the light the last change asked for (#1207).
     * Without this, the two copies are compared before the change has been worked out. A client
     * level has no engine of its own here and answers true.
     *
     * <p>This reads the engine's propagation queues, the checks in its task list and whether it has
     * a batch scheduled. A check still waiting in the chunk sorter's queue before it reaches that
     * task list is not visible here, which is why this is not a claim that two copies can never
     * agree while both are stale, only that they are not compared while the engine itself has work
     * (#1207).
     */
    @GrugGenerated(
            "client light: a client level never reaches this (the simulated side is the integrated"
                    + " server), and every 1.20.6 engine is the threaded one this reads")
    static boolean lightEngineSettled(Object levelObj) {
        if (!(levelObj instanceof ServerLevel serverLevel)) {
            return true;
        }
        ThreadedLevelLightEngine engine = serverLevel.getChunkSource().getLightEngine();
        boolean tasksQueued = false;
        boolean updateScheduled = false;
        if (engine instanceof GrugLightEngineState state) {
            tasksQueued = state.grug$hasQueuedLightTasks();
            updateScheduled = state.grug$isUpdateScheduled();
        }
        return GrugRenderSettle.serverLightSettled(
                engine.hasLightWork(), tasksQueued, updateScheduled);
    }

    /**
     * A section's block light as its 2048 bytes, or null when the level has no layer for it, which
     * is the same as a section nothing has lit.
     *
     * <p>Read from either side of the client-server pair. The client's store is written only by the
     * client thread, so a read taken from the server thread can see a section's layer before or
     * after an update. A read that lands before it reports the older light, which makes the settle
     * answer wait another tick; what is compared is the bytes the client holds, so the light it has
     * not been sent compares unequal rather than passing early. This is also the read that is safe
     * across the two threads: the client publishes its finished sections through a volatile field,
     * which this goes through, while a position's {@code getBrightness} reads the working store
     * with no such publication (#1207). {@code DataLayer.getData} allocates the array of a layer
     * that holds its default value instead of one, which for block light is no light at all.
     */
    @GrugGenerated(
            "settle: a level that does not exist and a section outside the light engine's height"
                    + " are reads a test's own room never asks for")
    static byte[] blockLightLayer(Object levelObj, int sectionX, int sectionY, int sectionZ) {
        if (levelObj == null) {
            return null;
        }
        return lightLayer(levelObj, LightLayer.BLOCK, sectionX, sectionY, sectionZ);
    }

    /**
     * One of a section's light layers as its 2048 bytes, or null when the level has no layer for
     * it.
     *
     * <p>Read from the layer the client draws from, which is the storage's {@code
     * visibleSectionData} rather than the copy {@code getDataLayerData} answers: that one returns a
     * light packet's queued layer before the render thread has swapped it in, so a read taken
     * between the packet and the swap holds data no frame has been meshed from yet. The swap
     * happens in the same frame, before the sections are compiled, so the visible layer and the
     * drawn light are the same thing. See #1266.
     */
    @GrugGenerated(
            "settle: a section the engine has no layer for is one outside its height, and a light"
                    + " engine this level has none of is one the Nether or the End has, neither of"
                    + " which a test's own room asks for")
    private static byte[] lightLayer(
            Object levelObj, LightLayer lightType, int sectionX, int sectionY, int sectionZ) {
        Level level = (Level) levelObj;
        LayerLightEventListener listener =
                level.getChunkSource().getLightEngine().getLayerListener(lightType);
        // A level whose light engine has no engine for a layer (the Nether and the End have no sky
        // light) answers a dummy listener rather than null, and it has no section data to read.
        if (!(listener instanceof LightEngine<?, ?>)) {
            return null;
        }
        LayerLightSectionStorage<?> storage =
                ((LightEngineStorageAccessor) listener).grug$storage();
        DataLayer layer =
                ((LayerLightSectionStorageAccessor) storage)
                        .grug$visibleSectionData()
                        .getLayer(SectionPos.asLong(sectionX, sectionY, sectionZ));
        return layer == null ? null : layer.getData();
    }

    /**
     * The client's frames, and whether the last one had work in it, for {@link #clientSettled}.
     *
     * <p>Static because the frame hook is a Forge render event and there is one client per process;
     * the event handler is the only caller of {@link #onFrameRendered}.
     */
    static final GrugRenderSettle RENDER_SETTLE = new GrugRenderSettle();

    /**
     * The client's copy of the blocks a test has placed. The test's edits reach the integrated
     * server in its own tick, and the client only after a packet, so a settle answer built from
     * frames alone can describe a world without them. See #804.
     */
    static final GrugBlockSync BLOCK_SYNC = new GrugBlockSync();

    @Override
    public boolean isLoaderReloading() {
        return GrugModLoader.isReloading();
    }

    @Override
    public void forgetSettledFrames() {
        RENDER_SETTLE.reset();
    }

    @Override
    public boolean registersModRecipes() {
        // Forge's own recipe manager does the work, because grug adds a pack whose data side is the
        // mods' data/<namespace> trees, so the level's RecipeManager loads grug:recipes/... itself.
        return true;
    }

    @Override
    public Vec3 testPlayerEye(Object playerObj) {
        Player player = (Player) playerObj;
        return new Vec3(player.getX(), player.getEyeY(), player.getZ());
    }

    @Override
    @GrugGenerated("test helper: it asks a game's own block for its collision boxes")
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
        Level level = (Level) levelObj;
        net.minecraft.core.BlockPos pos =
                new net.minecraft.core.BlockPos(
                        (int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
        AABB area =
                new AABB(
                        pos.getX() + minX,
                        pos.getY() + minY,
                        pos.getZ() + minZ,
                        pos.getX() + maxX,
                        pos.getY() + maxY,
                        pos.getZ() + maxZ);
        // The two-argument getCollisionShape answers the block state's cached shape, which is the
        // one built when the block was made and never follows a block whose collision changes with
        // its neighbours. The game's own collision code asks this four-argument form.
        VoxelShape shape =
                level.getBlockState(pos).getCollisionShape(level, pos, CollisionContext.empty());
        for (AABB box : shape.toAabbs()) {
            if (box.move(pos).intersects(area)) {
                return true;
            }
        }
        return false;
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
        Level level = (Level) levelObj;
        net.minecraft.core.BlockPos pos =
                new net.minecraft.core.BlockPos(
                        (int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
        net.minecraft.world.phys.Vec3 point =
                new net.minecraft.world.phys.Vec3(
                        pos.getX() + pointX, pos.getY() + pointY, pos.getZ() + pointZ);
        VoxelShape shape = level.getBlockState(pos).getShape(level, pos);
        for (AABB box : shape.toAabbs()) {
            if (box.move(pos).contains(point)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void addRedstoneDust(Object levelObj, double x, double y, double z) {
        // The dust particle takes its colour from its options and ignores the motion.
        ((Level) levelObj)
                .addParticle(
                        net.minecraft.core.particles.DustParticleOptions.REDSTONE,
                        x,
                        y,
                        z,
                        0,
                        0,
                        0);
    }

    @Override
    @GrugGenerated("test helper: it asks a game's own block for its display tick")
    public void displayTickForTest(Object levelObj, double x, double y, double z, long seed) {
        Level level = (Level) levelObj;
        net.minecraft.core.BlockPos pos =
                new net.minecraft.core.BlockPos(
                        (int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
        BlockState state = level.getBlockState(pos);
        state.getBlock().animateTick(state, level, pos, RandomSource.create(seed));
    }

    @Override
    @GrugGenerated("test helper: it asks the game's own block clip for the block's shape")
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
        Level level = (Level) levelObj;
        net.minecraft.core.BlockPos pos =
                new net.minecraft.core.BlockPos(
                        (int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));

        // The clip a player's click takes, against the outline shape rather than the collision one,
        // so this version answers through Block#getShape exactly as a click does.
        BlockHitResult hit =
                level.clip(
                        new ClipContext(
                                new net.minecraft.world.phys.Vec3(
                                        pos.getX() + startX,
                                        pos.getY() + startY,
                                        pos.getZ() + startZ),
                                new net.minecraft.world.phys.Vec3(
                                        pos.getX() + endX, pos.getY() + endY, pos.getZ() + endZ),
                                ClipContext.Block.OUTLINE,
                                ClipContext.Fluid.NONE,
                                CollisionContext.empty()));
        return hit.getType() != HitResult.Type.MISS && hit.getBlockPos().equals(pos);
    }
}
