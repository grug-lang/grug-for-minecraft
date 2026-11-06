package net.grug.minecraft.forge;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;

import net.grug.minecraft.core.GrugBlockSync;
import net.grug.minecraft.core.GrugLightSync;
import net.grug.minecraft.core.GrugRenderSettle;
import net.grug.minecraft.core.GrugSavedDataSync;
import net.grug.minecraft.core.GrugSide;
import net.grug.minecraft.core.GrugTestBox;
import net.grug.minecraft.core.GrugTestCamera;
import net.grug.minecraft.core.GrugTestPlayerSnap;
import net.grug.minecraft.core.GrugTestRunner;
import net.grug.minecraft.core.GrugTickLockstep;
import net.grug.minecraft.core.GrugUseBlockFace;
import net.grug.minecraft.core.GrugWorldReady;
import net.grug.minecraft.core.ModLoaderAdapter;
import net.grug.minecraft.forge.block.GrugBlock;
import net.grug.minecraft.forge.block.entity.GrugBlockEntity;
import net.grug.minecraft.forge.gui.GrugMenu;
import net.grug.minecraft.forge.mixin.GuiSavingIndicatorAccessor;
import net.grug.minecraft.grug.BlockPos;
import net.grug.minecraft.grug.Grug;
import net.grug.minecraft.grug.GrugBlockEntityNbt;
import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.grug.GrugOverlay;
import net.grug.minecraft.grug.GrugScreenshots;
import net.grug.minecraft.grug.GrugSlotAccess;
import net.grug.minecraft.grug.Vec3;
import net.grug.minecraft.gui.GrugGuiBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.sounds.SoundEvent;
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
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ForgeEventFactoryClient;
import net.minecraftforge.client.model.data.ModelData;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.server.ServerLifecycleHooks;

import org.slf4j.Logger;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;

public class ForgeAdapter implements ModLoaderAdapter {
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
        return FMLEnvironment.dist == Dist.DEDICATED_SERVER
                ? new ServerForgeAdapter()
                : new ForgeAdapter();
    }

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
    public GrugSide getSide() {
        return GrugSide.CLIENT;
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
        return Minecraft.getInstance().level;
    }

    @Override
    public Object testPlayer() {
        return Minecraft.getInstance().player;
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
    private static final float REAL_TIME_TICK_RATE = 20.0F;

    /**
     * The tick rate of the integrated server while the clock runs fast: high enough that the
     * schedule is never what holds a tick back, because the lockstep with the client is. See {@link
     * GrugTickLockstep}.
     */
    private static final float FAST_TICK_RATE = REAL_TIME_TICK_RATE * GrugTestRunner.MAX_GAME_SPEED;

    @Override
    public double maxGameSpeed() {
        return CLIENT_TICKS_PER_FRAME;
    }

    /**
     * Runs the world and the client's tick faster than real time, together.
     *
     * <p>Two clocks keep time here, and both are moved. The integrated server ticks the world, the
     * block entities and the runner itself on its own thread, and the client ticks from its own
     * timer, which never goes faster than 20 a second on the server's word, so {@code
     * MinecraftTimerMixin} scales it from {@link GrugGameClock}. The runner is queued from the
     * client's tick, so with only the server sped up it would run 20 times a second against a world
     * that ran many times as fast.
     *
     * <p>The client runs at most 10 ticks for a frame, so what a speed delivers is bounded by about
     * ten times the frame rate, and a server left to its tick rate would run ahead of what the
     * client can consume (1.64 times at 10x, 15 times at 100x). The server therefore gets a tick
     * rate that is never the limit, and {@code MinecraftServerTickMixin} holds each of its ticks
     * back until the client has made as many, so the world stays on the runner's clock.
     */
    @GrugGenerated(
            "the game's private tick timer: a dedicated server and a client without a world are"
                    + " never reached, and the rest is the scale, which CI sees only as how long a"
                    + " test takes")
    @Override
    public boolean setGameSpeed(double speed) {
        // A dedicated server has no client timer, and its tick rate is the server's own to set.
        if (getSide() != GrugSide.CLIENT) return false;
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null) return false;
        if (speed > 1) {
            GrugGameClock.LOCKSTEP.start();
            GrugGameClock.speed = (float) speed;
            server.tickRateManager().setTickRate(FAST_TICK_RATE);
        } else {
            GrugGameClock.speed = 1.0F;
            GrugGameClock.LOCKSTEP.stop();
            server.tickRateManager().setTickRate(REAL_TIME_TICK_RATE);
        }
        return true;
    }

    /**
     * Saves the world once, at the tick a test whose file name starts with {@code autosave_} asks
     * for, the way the game's own autosave does, so the save indicator is drawn on a tick the test
     * knows. See {@link GrugForcedAutosave}.
     */
    @Override
    public void requestAutosaveForTest() {
        Objects.requireNonNull(
                        Minecraft.getInstance().getSingleplayerServer(),
                        "a test-only autosave needs the integrated server")
                .saveEverything(true, false, false);
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
    public int getItemMaxStack(Object itemObj) {
        return new ItemStack((Item) itemObj).getMaxStackSize();
    }

    @Override
    public void giveItemToPlayer(Object playerObj, Object itemObj) {
        giveItemToPlayer((Player) playerObj, (Item) itemObj, 1);
    }

    @Override
    public void giveItemStackToPlayer(Object playerObj, Object itemObj) {
        Item item = (Item) itemObj;
        giveItemToPlayer((Player) playerObj, item, new ItemStack(item).getMaxStackSize());
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
        server.execute(() -> giveOnServerThread(player, item, count));
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

    /**
     * Whether the client holds the chunk a position is in.
     *
     * <p>A placement in a chunk the client has not received is written to the server's world, which
     * is the world a test reads here, and the client's copy of it stays void air: the chunk is only
     * sent once the player comes near it. Waiting for that copy would wait until the test's budget
     * ran out. The read is on the server thread, against the client's own chunk storage, whose
     * slots are atomic. See #804.
     */
    protected boolean clientHasChunk(int x, int z) {
        // Unguarded like the rest of the adapter, which only runs with a level: a grug Level is
        // taken from one. See #804.
        return Minecraft.getInstance().level.getChunkSource().hasChunk(x >> 4, z >> 4);
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
    @GrugGenerated("the no-screen arm: a run reads the screen it just opened")
    public Object carriedItem() {
        AbstractContainerScreen<?> container = openContainerScreen();
        if (container == null) return null;
        ItemStack carried = container.getMenu().getCarried();
        return carried.isEmpty() ? null : carried.getItem();
    }

    @Override
    @GrugGenerated("the no-screen arm: a run sets the cursor on the screen it just opened")
    public void setCarriedItem(Object itemObj) {
        onClientThread(() -> setCarriedItemOnClient(itemObj));
    }

    @GrugGenerated("the no-screen arm: a run sets the cursor on the screen it just opened")
    private void setCarriedItemOnClient(Object itemObj) {
        AbstractContainerScreen<?> container = openContainerScreen();
        if (container == null) return;
        container
                .getMenu()
                .setCarried(itemObj == null ? ItemStack.EMPTY : new ItemStack((Item) itemObj, 1));
    }

    @Override
    public boolean hasSound(String path) {
        return BuiltInRegistries.SOUND_EVENT.containsKey(new ResourceLocation("minecraft", path));
    }

    @Override
    public void playUiSound(String path, double volume, double pitch) {
        // The sound engine belongs to the client thread, and a test calls this from the server's.
        onClientThread(
                () -> {
                    SoundEvent event =
                            BuiltInRegistries.SOUND_EVENT.get(
                                    new ResourceLocation("minecraft", path));
                    Minecraft.getInstance()
                            .getSoundManager()
                            .play(SimpleSoundInstance.forUI(event, (float) pitch, (float) volume));
                });
    }

    @Override
    public void deleteCarriedItem() {
        // The click pass calls this on the client thread, but a test's own clear runs on the server
        // thread, so the client's menu is touched where it belongs either way.
        onClientThread(this::deleteCarriedItemOnClient);
    }

    @GrugGenerated(
            "the no-screen and no-server arms: a run deletes from the screen it just opened"
                    + " on an integrated server")
    private void deleteCarriedItemOnClient() {
        AbstractContainerScreen<?> container = openContainerScreen();
        if (container == null) return;
        container.getMenu().setCarried(ItemStack.EMPTY);

        // The screen's menu is the client's copy, so the server keeps its own carried stack until
        // it is cleared too, which is the half JEI's delete packet does. A client of a remote
        // server has no server to reach from here, which about.json records with the give.
        Player local = Minecraft.getInstance().player;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (local == null || server == null) return;
        server.execute(() -> clearServerCarried(server, local));
    }

    @GrugGenerated(
            "a missing server player: a run deletes from the screen it just opened on an"
                    + " integrated server")
    private static void clearServerCarried(MinecraftServer server, Player local) {
        ServerPlayer serverPlayer = server.getPlayerList().getPlayer(local.getUUID());
        if (serverPlayer != null) {
            serverPlayer.containerMenu.setCarried(ItemStack.EMPTY);
        }
    }

    @GrugGenerated("the no-screen arm: a run reads the screen it just opened")
    private static AbstractContainerScreen<?> openContainerScreen() {
        Screen screen = Minecraft.getInstance().screen;
        return screen instanceof AbstractContainerScreen<?> container ? container : null;
    }

    @Override
    @GrugGenerated("the no-screen arm: a run measures the screen it just opened")
    public double screenWidthForTest() {
        if (refuseWithoutAClient("Test.screen_width")) return 0.0;
        Screen screen = Minecraft.getInstance().screen;
        return screen == null ? 0.0 : screen.width;
    }

    @Override
    @GrugGenerated("the no-screen arm: a run measures the screen it just opened")
    public double guiLeftForTest() {
        if (refuseWithoutAClient("Test.gui_left")) return 0.0;
        AbstractContainerScreen<?> container = openContainerScreen();
        return container == null ? 0.0 : container.getGuiLeft();
    }

    @Override
    @GrugGenerated("the no-screen arm: a run measures the screen it just opened")
    public double guiWidthForTest() {
        if (refuseWithoutAClient("Test.gui_width")) return 0.0;
        AbstractContainerScreen<?> container = openContainerScreen();
        return container == null ? 0.0 : container.getXSize();
    }

    @Override
    @GrugGenerated("the no-screen arm: a run measures the screen it just opened")
    public double guiTopForTest() {
        if (refuseWithoutAClient("Test.gui_top")) return 0.0;
        AbstractContainerScreen<?> container = openContainerScreen();
        return container == null ? 0.0 : container.getGuiTop();
    }

    @Override
    @GrugGenerated("the no-screen arm: a run measures the screen it just opened")
    public double guiHeightForTest() {
        if (refuseWithoutAClient("Test.gui_height")) return 0.0;
        AbstractContainerScreen<?> container = openContainerScreen();
        return container == null ? 0.0 : container.getYSize();
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
    private static String blockNameAt(Level world, net.minecraft.core.BlockPos pos) {
        Block block = world.getBlockState(pos).getBlock();
        return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block).toString();
    }

    private static String blockNameAt(Level world, int x, int y, int z) {
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
    public boolean isWorldSettled() {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        // What the client owes is the simulated world's current block, not the one the test placed:
        // a clear can be refilled by water. This runs on the server thread, which owns that world.
        Object simulated = getLevel();
        // The server's half comes first: it is the half that can be about to change, and a light
        // change the light thread applies between two reads below can then only make the light
        // comparison fail, never pass early. The client's half is read last, because the frame it
        // counts is the one a change dirties. See #686.
        if (!serverSettled(minecraft, level)) {
            return false;
        }
        if (!GrugLightSync.roomSettled(
                (x, y, z) -> blockLightLayer(simulated, x, y, z),
                (x, y, z) -> blockLightLayer(level, x, y, z))) {
            return false;
        }
        return clientSettled(minecraft, simulated);
    }

    @Override
    public boolean separatesClientLight() {
        return true;
    }

    /**
     * A section's block light as its 2048 bytes, or null when the level has no layer for it, which
     * is the same as a section nothing has lit.
     *
     * <p>Read from either side of the client-server pair. The client's store is written only by the
     * client thread, so a read taken from the server thread can see a section's layer before or
     * after an update. A read that lands before it reports the older light, which makes the settle
     * answer wait another tick; what is compared is the bytes the client holds, so the light it has
     * not been sent compares unequal rather than passing early. {@code DataLayer.getData} allocates
     * the array of a layer that holds its default value instead of one, which for block light is no
     * light at all.
     */
    @GrugGenerated(
            "settle: a level that does not exist and a section outside the light engine's height"
                    + " are reads a test's own room never asks for")
    private static byte[] blockLightLayer(
            Object levelObj, int sectionX, int sectionY, int sectionZ) {
        if (levelObj == null) {
            return null;
        }
        Level level = (Level) levelObj;
        net.minecraft.world.level.chunk.DataLayer layer =
                level.getChunkSource()
                        .getLightEngine()
                        .getLayerListener(LightLayer.BLOCK)
                        .getDataLayerData(SectionPos.of(sectionX, sectionY, sectionZ));
        return layer == null ? null : layer.getData();
    }

    /**
     * The client's frames, and whether the last one had work in it, for {@link #clientSettled}.
     *
     * <p>Static because the frame hook is a Forge render event and there is one client per process;
     * the event handler is the only caller of {@link #onFrameRendered}.
     */
    private static final GrugRenderSettle RENDER_SETTLE = new GrugRenderSettle();

    /**
     * The client's copy of the blocks a test has placed. The test's edits reach the integrated
     * server in its own tick, and the client only after a packet, so a settle answer built from
     * frames alone can describe a world without them. See #804.
     */
    private static final GrugBlockSync BLOCK_SYNC = new GrugBlockSync();

    /**
     * One completed frame, called from the render event handler: the level has been uploaded and
     * drawn, so whether it still had work in it is the answer a capture reading this frame needs.
     *
     * <p>The light engine is not sampled here: a frame is drawn after the client drained it, and
     * the test's own wait and the capture's re-check ask it at the moment they run, which is where
     * light work that arrives between two frames is caught.
     */
    @Override
    public void onFrameRendered() {
        Minecraft minecraft = Minecraft.getInstance();
        // The reader only runs while an edit is owed, and only a placement in a level records one,
        // so there is a client level whenever it is asked. This event fires after a level was
        // drawn, and a frame drawn before a world exists owes nothing. The client's copy is only
        // safe to read on this thread, where the packets that change it are applied, which is why
        // the frame hook is the one place it is sampled. See #804.
        boolean blocksPending =
                BLOCK_SYNC.onFrame((x, y, z) -> blockNameAt(minecraft.level, x, y, z));
        // A frame drawn from a view the mouse moved is not one a capture may read, and the view is
        // levelled again for the next frame. See GrugTestCamera and #1128.
        RENDER_SETTLE.onFrame(
                GrugTestCamera.frameBusy(
                        !rendererIdle(minecraft), testPlayerSnapped, testPlayer(), CAMERA_VIEW),
                blocksPending);
    }

    /**
     * This generation's view of the local player: its yaw and pitch, and the pair a frame
     * interpolates from.
     */
    private static final GrugTestCamera.View CAMERA_VIEW =
            new GrugTestCamera.View() {
                @Override
                public float yaw(Object player) {
                    return ((Player) player).getYRot();
                }

                @Override
                public float pitch(Object player) {
                    return ((Player) player).getXRot();
                }

                @Override
                public float previousYaw(Object player) {
                    return ((Player) player).yRotO;
                }

                @Override
                public float previousPitch(Object player) {
                    return ((Player) player).xRotO;
                }

                @Override
                public void level(Object player) {
                    Player target = (Player) player;
                    target.setYRot(0.0F);
                    target.setXRot(0.0F);
                    target.yRotO = 0.0F;
                    target.xRotO = 0.0F;
                }
            };

    @Override
    public boolean isLoaderReloading() {
        return GrugModLoader.isReloading();
    }

    private static final int SCAN_IDLE = 0;
    private static final int SCAN_PENDING = 1;
    private static final int SCAN_DONE = 2;

    /** Where the scan for {@link #hasStaleTestBlockEntities} is, and what it found when done. */
    private volatile int staleScan = SCAN_IDLE;

    private volatile boolean staleScanFound = false;

    /**
     * Whether the client's chunks still hold a grug block entity inside the test area that the room
     * build has cleared on the server. The server's packets about the clearing reach the client a
     * tick or more after the build, and until it has processed them a rebuild of one of its
     * sections draws the old block entity on a chunk worker thread. That draw enters the VM while
     * the test's first call waits on the client thread with the state lock released, and an err it
     * reports there stops the test's own call. See #583.
     *
     * <p>The chunks' block entity maps are the client thread's to change, and iterating one from
     * another thread can fail halfway, so the scan runs on the client thread. The runner asks from
     * the server thread, and a wait for the client thread there would hold the server up for as
     * long as the client is busy, which after a resource reload is seconds, and the server then
     * ticks in a burst to catch up and runs a test's waits out before the client has done anything.
     * So the scan is posted and not waited for: this answers true while it has not come back, and
     * asks again after a scan that found one.
     */
    @Override
    @GrugGenerated(
            "client plumbing: the answer depends on how far the client is behind the server, which"
                    + " a run cannot choose")
    public boolean hasStaleTestBlockEntities() {
        if (FMLEnvironment.dist != Dist.CLIENT) return false;
        Player player = (Player) testPlayer();
        if (player == null) return false;
        if (staleScan == SCAN_PENDING) return true;
        if (staleScan == SCAN_DONE) {
            staleScan = SCAN_IDLE;
            if (!staleScanFound) return false;
        }
        double feetX = player.getX();
        double feetY = player.getY();
        double feetZ = player.getZ();
        staleScan = SCAN_PENDING;
        Minecraft.getInstance()
                .execute(
                        () -> {
                            staleScanFound = clientHoldsBlockEntityInTestArea(feetX, feetY, feetZ);
                            staleScan = SCAN_DONE;
                        });
        return true;
    }

    @GrugGenerated("client plumbing: see hasStaleTestBlockEntities")
    private static boolean clientHoldsBlockEntityInTestArea(
            double feetX, double feetY, double feetZ) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return false;
        int radius = GrugTestBox.areaRadius();
        int minChunkX = ((int) Math.floor(feetX) - radius) >> 4;
        int maxChunkX = ((int) Math.floor(feetX) + radius) >> 4;
        int minChunkZ = ((int) Math.floor(feetZ) - radius) >> 4;
        int maxChunkZ = ((int) Math.floor(feetZ) + radius) >> 4;
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                LevelChunk chunk =
                        level.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
                if (chunk == null) continue;
                for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
                    net.minecraft.core.BlockPos at = blockEntity.getBlockPos();
                    if (blockEntity instanceof GrugBlockEntity
                            && !blockEntity.isRemoved()
                            && GrugTestBox.inClearedArea(
                                    feetX, feetY, feetZ, at.getX(), at.getY(), at.getZ())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    @Override
    public void forgetSettledFrames() {
        RENDER_SETTLE.reset();
    }

    /**
     * Whether the client's own rendering state has nothing left that a frame's bake depends on: its
     * light engine is idle, and the last frame it drew had neither queued nor in-flight compiles.
     *
     * <p>This is the half of the settle answer a screenshot wait can hold for on its own, because
     * it advances on the client tick while the wait runs on the server thread and blocks it: a wait
     * that included the server half would freeze the server light work it was waiting for, since
     * that engine only drains on the server tick. The server half is the test's own wait in {@link
     * #isWorldSettled}, one call per test tick, where the server is free to tick. See #259.
     *
     * <p>The frame rule is what the renderer's queues cannot say by themselves. A compile that
     * finishes on a worker thread leaves the queues empty between two frames, and the frame that
     * uploads and draws its output has not been drawn yet, so a capture taken then would read the
     * frame from before the compile. See #257.
     *
     * <p>A client resource reload that is waiting to start or has not finished keeps this false:
     * the render thread spends it stitching atlases instead of processing what the server sent, so
     * frames drawn meanwhile end idle before the client has seen a test's own setup, and a settled
     * answer built from them describes a world that is not there yet. See #328.
     */
    private static boolean clientSettled(Minecraft minecraft, Object simulated) {
        // All four reads happen every time and the rule is GrugRenderSettle.clientSettled, a pure
        // function that its unit test covers in every combination, so this method has no branch of
        // its own that only a poll landing while light packets are queued, or a frame landing while
        // a test's block edit is owed, could meet. See #424 and #804.
        return !GrugModLoader.isReloading()
                && GrugRenderSettle.clientSettled(
                        minecraft.level.getChunkSource().getLightEngine().hasLightWork(),
                        minecraft.level.isLightUpdateQueueEmpty(),
                        RENDER_SETTLE.settled(),
                        // The client can be lit and idle while a test's own block is still a packet
                        // away: only its copy of the block says whether the frame a capture reads
                        // shows the edit. The answer refreshes from the simulated world first, so a
                        // capture whose wait has no settle call in front of it compares the client
                        // against what the world holds now rather than against a block the world
                        // replaced. See #804.
                        BLOCK_SYNC.settled((x, y, z) -> blockNameAt((Level) simulated, x, y, z)));
    }

    /**
     * Whether the client's chunk renderer has nothing left that a capture would have to be taken
     * after: no queued or finished compile, and none in flight.
     *
     * <p>{@code LevelRenderer.hasRenderedAllSections} is {@code
     * SectionRenderDispatcher.isQueueEmpty}, and that counts queued and finished compiles only: a
     * compile a worker is running has been taken off the queues and holds one of the section buffer
     * pool's buffers, so the pool's free count is what says one is in flight. A capture taken while
     * it runs reads the frame from before that compile's light, which is the one-step band in the
     * crop's corner. The pool's size is recorded when it is built, by the mixin next to this
     * class's loader. See #259.
     */
    @GrugGenerated(
            "settle: the in-flight phase is a compile running while the queues are empty, which a"
                    + " run reaches at the tail of a batch but cannot force; the idle answer as a"
                    + " whole stays measured through the caller's outcome")
    private static boolean rendererIdle(Minecraft minecraft) {
        if (!minecraft.levelRenderer.hasRenderedAllSections()) {
            return false;
        }
        return minecraft.levelRenderer.getSectionRenderDispatcher().getFreeBufferCount()
                == GrugModLoader.getSectionBufferPoolSize();
    }

    /**
     * Whether the integrated server's light engine is done, or there is no server to ask.
     *
     * <p>A singleplayer client's light comes from the integrated server, so the server has to be
     * done too: its updates reach the client as packets, and the client can look idle while the
     * server is still computing them. The lookup is loader plumbing: a singleplayer client run has
     * a server and the client's own dimension. The no-server arm is the client of a dedicated
     * server, which the multiplayer legs launch and take on every forwarded settle ask (#1086), and
     * the no-level arm is a dimension the server does not have.
     *
     * <p>The public light-engine answer counts only that engine's propagation queues, so the checks
     * a batch has not reached yet and the batch itself are read from the engine's own state, which
     * the mixin next to this loader's mixins exposes. See {@link
     * GrugRenderSettle#serverLightSettled} and #686.
     */
    @GrugGenerated(
            "settle: the integrated server lookup is loader plumbing a CI client run cannot"
                    + " observe, and so is the arm where the engine is not the threaded one")
    private boolean serverSettled(Minecraft minecraft, ClientLevel level) {
        MinecraftServer server = minecraft.getSingleplayerServer();
        if (server == null) {
            return true;
        }
        ServerLevel serverLevel = server.getLevel(level.dimension());
        if (serverLevel == null) {
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

    private boolean testPlayerSnapped = false;
    private double savedX, savedY, savedZ;
    private float savedYaw, savedPitch;

    @Override
    @GrugGenerated("screenshot/GL integration: failure paths a healthy run cannot enter")
    public void snapPlayerForTestOnClient() {
        // The test runner is driven from the integrated server thread, but the camera is the client
        // player's, so client-side work is marshalled onto the client (render) thread.
        onClientThread(this::snapPlayerOnClient);
    }

    @GrugGenerated("screenshot/GL integration: failure paths a healthy run cannot enter")
    private void snapPlayerOnClient() {
        Player player = Minecraft.getInstance().player;
        if (player == null) {
            throw Grug.fatal(
                    "The test runner: there is no local player to snap to their block centre.");
        }

        savedX = player.getX();
        savedY = player.getY();
        savedZ = player.getZ();
        savedYaw = player.getYRot();
        savedPitch = player.getXRot();
        testPlayerSnapped = true;

        // Snap the player to the centre of their own block and level the view, so every loader
        // frames a fixture identically and the frame does not depend on which way the player was
        // looking. The snap stays inside the player's own block, and therefore inside the chunk
        // already loaded and lit around them, so unlike a far teleport it cannot drag the camera
        // into an unlit chunk. The runner restores this position when the test ends.
        player.setYRot(0.0F);
        player.setXRot(0.0F);
        player.setPos(
                GrugTestPlayerSnap.blockCentre(savedX),
                savedY,
                GrugTestPlayerSnap.blockCentre(savedZ));
    }

    @Override
    @GrugGenerated("screenshot/GL integration: failure paths a healthy run cannot enter")
    public void restorePlayerAfterTest() {
        // A no-op on a dedicated server, where there is no client player to snap. The runner calls
        // this for every test that ends, so it must not report an error for those.
        if (!testPlayerSnapped) return;
        testPlayerSnapped = false;

        onClientThread(this::restorePlayerOnClient);
    }

    @GrugGenerated("screenshot/GL integration: failure paths a healthy run cannot enter")
    private void restorePlayerOnClient() {
        Player player = Minecraft.getInstance().player;
        if (player != null) {
            player.setPos(savedX, savedY, savedZ);
            player.setYRot(savedYaw);
            player.setXRot(savedPitch);
        }
    }

    @Override
    public void closeOpenScreenForTest() {
        // Marshalled like the other graphics calls, because the runner advances on the server
        // thread while the screen belongs to the client. onClientThread waits for the task, so the
        // screen is gone by the time this returns and not merely by the next client tick.
        onClientThread(() -> Minecraft.getInstance().setScreen(null));
    }

    @Override
    public boolean isScreenOpenForTest() {
        return Minecraft.getInstance().screen != null;
    }

    @Override
    @GrugGenerated("the refusal arm needs a dedicated server, whose coverage no CI run merges")
    public void openInventory(Object playerObj) {
        if (refuseWithoutAClient("Test.open_inventory")) return;
        Player player = (Player) playerObj;
        // Marshalled like closeOpenScreenForTest, because a test ticks on the server thread and the
        // screen belongs to the client. The screen is up by the next frame, which a test waits for
        // with Test.screen_open.
        onClientThread(() -> Minecraft.getInstance().setScreen(new InventoryScreen(player)));
    }

    @Override
    @GrugGenerated("the no-screen arm: a run presses while the screen it just opened is up")
    public boolean clickScreen(double x, double y, double button) {
        if (refuseWithoutAClient("Test.click_screen")) return false;
        // The same path a real press takes, fired on the client thread, so the overlay's handler
        // and the screen's own handler see exactly what a mouse would send, including a
        // cancellation. The arms live in clickOnClient so that this method has no branch for a
        // unit test to be unable to reach.
        boolean[] outcome = new boolean[2];
        onClientThread(() -> clickOnClient(x, y, (int) button, outcome));
        // Reported here rather than on the client thread: the grug call runs on the server thread,
        // and a host function error raised from another thread would not reach it.
        if (outcome[0]) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr, "Test.click_screen: There is no screen open to click.");
        }
        return outcome[1];
    }

    @GrugGenerated("the no-screen arm: a run presses while the screen it just opened is up")
    private void clickOnClient(double x, double y, int button, boolean[] outcome) {
        Screen screen = Minecraft.getInstance().screen;
        if (screen == null) {
            outcome[0] = true;
            return;
        }
        // The answer is the overlay's own decision, not the screen's: a container screen handles a
        // press on a vanilla slot too, so asking the screen would answer true with no overlay in
        // the way. The record is cleared before the press and read after the overlay had its say.
        GrugOverlay.beginTestPress();
        boolean[] state = new boolean[] {false};
        ForgeEventFactoryClient.onScreenMouseClicked(state, screen, x, y, button);
        outcome[1] = GrugOverlay.testPressTaken();
    }

    @Override
    @GrugGenerated("the no-screen arm: a run scrolls while the screen it just opened is up")
    public boolean scrollScreen(double x, double y, double delta) {
        if (refuseWithoutAClient("Test.scroll_screen")) return false;
        // The same path a real wheel takes: the pre event the overlay listens to, fired on the
        // client thread, and the screen's own handler only when nothing cancelled it.
        boolean[] outcome = new boolean[2];
        onClientThread(() -> scrollOnClient(x, y, delta, outcome));
        if (outcome[0]) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr, "Test.scroll_screen: There is no screen open to scroll.");
        }
        return outcome[1];
    }

    @GrugGenerated("the no-screen arm: a run scrolls while the screen it just opened is up")
    private void scrollOnClient(double x, double y, double delta, boolean[] outcome) {
        Screen screen = Minecraft.getInstance().screen;
        if (screen == null) {
            outcome[0] = true;
            return;
        }
        GrugOverlay.beginTestPress();
        if (!ForgeEventFactoryClient.onScreenMouseScrollPre(screen, x, y, 0.0, delta)) {
            screen.mouseScrolled(x, y, 0.0, delta);
        }
        outcome[1] = GrugOverlay.testPressTaken();
    }

    @Override
    @GrugGenerated("the no-screen arm: a run presses while the screen it just opened is up")
    public boolean pressScreenKey(int key, boolean ctrl, boolean release) {
        if (refuseWithoutAClient("Test.press_screen_key")) return false;
        // The same path a real key takes: the pre event the overlay listens to, fired on the
        // client thread, and the screen's own handler only when nothing cancelled it.
        boolean[] outcome = new boolean[2];
        onClientThread(() -> pressKeyOnClient(key, ctrl, release, outcome));
        if (outcome[0]) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr, "Test.press_screen_key: There is no screen open to press.");
        }
        return outcome[1];
    }

    @GrugGenerated("the no-screen arm: a run presses while the screen it just opened is up")
    private void pressKeyOnClient(int key, boolean ctrl, boolean release, boolean[] outcome) {
        Screen screen = Minecraft.getInstance().screen;
        if (screen == null) {
            outcome[0] = true;
            return;
        }
        GrugOverlay.beginTestPress();
        int modifiers = ctrl ? GrugOverlayHost.controlModifier() : 0;
        if (!ForgeEventFactoryClient.onScreenKeyPressedPre(screen, key, 0, modifiers)) {
            screen.keyPressed(key, 0, modifiers);
        }
        outcome[1] = GrugOverlay.testPressTaken();
        if (release) {
            ForgeEventFactoryClient.onScreenKeyReleasedPre(screen, key, 0, modifiers);
        }
    }

    @Override
    @GrugGenerated("screenshot/GL integration: failure paths a healthy run cannot enter")
    public void useBlockForTest(Object levelObj, double x, double y, double z) {
        if (refuseWithoutAClient("Test.use_block")) return;
        onClientThread(() -> useBlockOnClient(x, y, z));
    }

    @GrugGenerated("screenshot/GL integration: failure paths a healthy run cannot enter")
    private void useBlockOnClient(double x, double y, double z) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.gameMode == null) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr, "Test.use_block: There is no local player to right-click with.");
            return;
        }
        net.minecraft.core.BlockPos pos =
                new net.minecraft.core.BlockPos(
                        (int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
        // Drive a real right-click through the interaction manager, so the block's own use code
        // runs rather than a copy of its GUI layout. The face is the one a ray from the test
        // player's eye to the block's centre enters, which is the face the player sees of it and
        // the face the server path picks from the same eye (#1106).
        Direction face =
                Direction.values()[
                        GrugUseBlockFace.face(
                                testPlayerEye(mc.player), pos.getX(), pos.getY(), pos.getZ())];
        BlockHitResult hit =
                new BlockHitResult(
                        new net.minecraft.world.phys.Vec3(x + 0.5, y + 0.5, z + 0.5),
                        face,
                        pos,
                        false);
        mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
    }

    @Override
    @GrugGenerated(
            "failure paths a healthy run cannot enter: no local player or client level, no block at"
                    + " the position, or a shape the overlay failed to reset")
    public boolean miningCrackForTest(Object levelObj, double x, double y, double z) {
        if (refuseWithoutAClient("Test.mining_crack")) return false;
        final net.minecraft.core.BlockPos pos =
                new net.minecraft.core.BlockPos(
                        (int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
        final boolean[] result = new boolean[1];
        // The baked model and its model data are client objects, and the render thread reads the
        // manager this asks for, so the whole question is asked on that thread.
        onClientThread(() -> result[0] = miningCrackOnClient(pos));
        return result[0];
    }

    @GrugGenerated(
            "failure paths a healthy run cannot enter: no local player or client level, no block at"
                    + " the position, or a shape the overlay failed to reset")
    private static boolean miningCrackOnClient(net.minecraft.core.BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr, "Test.mining_crack: There is no client level to draw in.");
            return false;
        }

        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof GrugBlock)) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr,
                    "Test.mining_crack: There is no grug block at "
                            + pos.getX()
                            + ", "
                            + pos.getY()
                            + ", "
                            + pos.getZ()
                            + ".");
            return false;
        }

        // The overlay draws the block's model with the model data the model data manager holds, so
        // asking for its quads the same way is the same question the overlay asks. The block
        // entity answers with its position and level, and without them the model has no place to
        // find it and draws nothing, which is what a mined block showed before this. See #201.
        ModelData data = level.getModelDataManager().getAt(pos);
        List<BakedQuad> quads =
                mc.getBlockRenderer()
                        .getBlockModel(state)
                        .getQuads(
                                state,
                                null,
                                RandomSource.create(0L),
                                data == null ? ModelData.EMPTY : data,
                                null);
        return !quads.isEmpty();
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

    @Override
    public BufferedImage captureScreenshot(double x1, double y1, double x2, double y2) {
        // No settle wait for the frame itself: the frame this reads is one drawn after the test
        // asked, and Screenshot.equals compares it against a committed PNG. The world-save
        // indicator is the exception, because it outlives the save that put it up and is drawn into
        // the frames after it: the read is served at the end of the first frame drawn after this
        // call whose own sample says the text is not in it. See #1132.
        return captureChecked("Screenshot.capture", x1, y1, x2, y2);
    }

    /** The capture a test's thread is waiting for, served by {@link #onFrameEnded}. */
    private static final class PendingCapture {
        final int x1;
        final int y1;
        final int x2;
        final int y2;

        /**
         * {@link GrugRenderSettle#framesDrawn()} when the test asked, so that the frame read is one
         * drawn after the ask rather than the one already on the buffer.
         */
        final long framesWhenAsked;

        /**
         * The rectangle the frame's end read, which the asking thread returns once {@link #done}.
         */
        BufferedImage image;

        /** Counted down by the frame that read the rectangle, which is what the ask waits on. */
        final CountDownLatch done = new CountDownLatch(1);

        PendingCapture(int x1, int y1, int x2, int y2, long framesWhenAsked) {
            this.x1 = x1;
            this.y1 = y1;
            this.x2 = x2;
            this.y2 = y2;
            this.framesWhenAsked = framesWhenAsked;
        }
    }

    /**
     * The capture a test's thread is waiting for, or null.
     *
     * <p>Written by the test's thread and read by the client thread at the end of every frame, so
     * it is volatile. Only one capture runs at a time: the runner makes one grug call at a time,
     * and a capture does not return until its frame has served it.
     */
    private static volatile PendingCapture pendingCapture;

    /**
     * Serves the capture a test is waiting for, at the end of the frame just drawn, once that frame
     * is one a capture may read.
     *
     * <p>Called from the render tick's end on the client thread, after the frame is complete, so
     * the check and the read describe the same frame and no frame can be drawn in between. That is
     * what the previous wait could not promise: it ran on the test's thread and the read followed a
     * frame later, so a frame whose client tick raised the indicator was never checked and could be
     * the one read (#1132). Waiting here rather than in one test is what makes every capture on
     * this loader unable to read the text.
     *
     * <p>The frame may serve only if it was drawn after the test asked and its own sample says the
     * text is not in it; see {@link GrugRenderSettle#captureable}. A frame that is not served yet
     * leaves the request in place, and the next frame's end asks again.
     */
    static void onFrameEnded() {
        PendingCapture capture = pendingCapture;
        if (capture == null) {
            return;
        }
        // Sampled after the frame was drawn and before the next client tick, so the values are the
        // ones this frame's GUI pass read. See #1132.
        if (!RENDER_SETTLE.captureable(capture.framesWhenAsked, savingIndicatorShown())) {
            return;
        }
        capture.image = capture(capture.x1, capture.y1, capture.x2, capture.y2);
        // Cleared before the countdown, so that a frame which ends before the asking thread has
        // taken its image cannot serve the same request twice and replace what it returns.
        pendingCapture = null;
        capture.done.countDown();
    }

    /**
     * Asks for a capture and waits for the frame's end to serve it.
     *
     * <p>Runs on the thread the test captured from, releasing the grug state lock while it waits,
     * since the client has to keep drawing for a frame to serve the request. Only the client thread
     * is admitted into the state for the duration: it is the thread that draws those frames, and a
     * chunk worker that entered meanwhile and drew a block entity would fold its err into the
     * suspended call (#583, #836). See {@link #releaseStateForFrameEndWait}.
     */
    private static BufferedImage captureOnFrameEnd(int x1, int y1, int x2, int y2) {
        PendingCapture capture = new PendingCapture(x1, y1, x2, y2, RENDER_SETTLE.framesDrawn());
        pendingCapture = capture;
        try {
            Runnable wait = () -> awaitLatch(capture.done);
            releaseStateForFrameEndWait(wait);
        } finally {
            pendingCapture = null;
        }
        return capture.image;
    }

    /**
     * Picks the release form for the wait: the one that admits the client thread, or the plain one
     * for a capture before the client has ticked once.
     *
     * <p>Only that null arm is unreachable in a run, since a capture runs after the client's first
     * tick, which is what sets the field. The admitted arm is {@link #releaseWithAClientThread},
     * which is measured like any other method and runs on every capture.
     */
    @GrugGenerated(
            "frame-end capture wait: a capture runs after the client's first tick, so clientThread"
                    + " is set and this null arm cannot occur")
    private static void releaseStateForFrameEndWait(Runnable wait) {
        Thread admitted = clientThread;
        if (admitted == null) {
            releaseWithoutAClientThread(wait);
            return;
        }
        releaseWithAClientThread(wait, admitted);
    }

    /**
     * Releases the grug state lock for the wait, admitting only the client thread: it is the thread
     * that draws the frame the capture is served by, and a chunk worker that entered meanwhile and
     * drew a block entity would fold its err into the suspended call (#583, #836).
     */
    private static void releaseWithAClientThread(Runnable wait, Thread admitted) {
        Grug.runWithStateLockReleased(wait, admitted);
    }

    /**
     * The release form for a capture before the client has ticked once, which no run reaches; the
     * plain form lets every thread in, so the wait could not be kept out of the render thread.
     */
    @GrugGenerated("frame-end capture wait: the client has ticked before any capture runs")
    private static void releaseWithoutAClientThread(Runnable wait) {
        Grug.runWithStateLockReleased(wait);
    }

    /**
     * Whether the frame the client is drawing would have the save indicator in it, from the two
     * values {@code Gui} fades it with and the player's option.
     *
     * <p>Sampled from the render tick's end, on the client thread, after the frame was drawn and
     * before the next client tick, so the values are the ones this frame's GUI pass read. See
     * #1132.
     */
    private static boolean savingIndicatorShown() {
        Minecraft minecraft = Minecraft.getInstance();
        GuiSavingIndicatorAccessor indicator = (GuiSavingIndicatorAccessor) minecraft.gui;
        return GrugRenderSettle.savingIndicatorShown(
                minecraft.options.showAutosaveIndicator().get(),
                indicator.grug$savingIndicatorValue(),
                indicator.grug$lastSavingIndicatorValue());
    }

    /**
     * Checks the rectangle and reads it, reporting {@code operation} as the thing that wanted it.
     */
    @GrugGenerated("screenshot/GL integration: failure paths a healthy run cannot enter")
    private BufferedImage captureChecked(
            String operation, double x1, double y1, double x2, double y2) {
        // Validate the arguments before touching GL, so a typo'd coordinate is reported as a typo
        // rather than as a mysterious capture failure.
        String bad = GrugScreenshots.rectangleProblem(x1, y1, x2, y2);
        if (bad != null) {
            Grug.hostFunctionErrorHappened(Grug.statePtr, operation + ": " + bad);
            return null;
        }
        int left = (int) x1;
        int top = (int) y1;
        int right = (int) x2;
        int bottom = (int) y2;

        // A defensive safety net rather than the primary sizing mechanism: R forces 1280x720 around
        // a test run. If that ever stops happening, a mismatch here is much easier to diagnose than
        // a screen of subtly wrong pixels.
        Minecraft mc = Minecraft.getInstance();
        if (mc.getWindow().getWidth() != GrugScreenshots.WIDTH
                || mc.getWindow().getHeight() != GrugScreenshots.HEIGHT) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr,
                    operation
                            + ": the window is "
                            + mc.getWindow().getWidth()
                            + "x"
                            + mc.getWindow().getHeight()
                            + ", but screenshot tests are captured at "
                            + GrugScreenshots.WIDTH
                            + "x"
                            + GrugScreenshots.HEIGHT);
            return null;
        }

        return captureOnFrameEnd(left, top, right, bottom);
    }

    /**
     * Reads back a rectangle of the last rendered frame. Runs on the client thread and downloads
     * the main render target's colour texture (the same path vanilla screenshots use), which is
     * Y-flipped so the image's origin is the top left, matching the crop convention.
     */
    @GrugGenerated(
            "a joined client's forwarded capture, which only the multiplayer job runs (#1040)")
    static BufferedImage captureRectangle(int x1, int y1, int x2, int y2) {
        return capture(x1, y1, x2, y2);
    }

    /**
     * Whether a joined client's renderer has nothing left to show of what the server sent, for the
     * forwarded capture of the test-control channel (#1040): no resource reload, no light work, and
     * frames drawn since that ended idle. The block half of {@link #clientSettled} is left out,
     * because it tracks edits made on a client's own side of an integrated server, and a joined
     * client makes none.
     */
    @GrugGenerated(
            "a joined client's forwarded capture, which only the multiplayer job runs (#1040)")
    static boolean clientIdleForControl(Minecraft minecraft) {
        return !GrugModLoader.isReloading()
                && GrugRenderSettle.clientSettled(
                        minecraft.level.getChunkSource().getLightEngine().hasLightWork(),
                        minecraft.level.isLightUpdateQueueEmpty(),
                        RENDER_SETTLE.settled(),
                        true);
    }

    private static BufferedImage capture(int x1, int y1, int x2, int y2) {
        RenderTarget target = Minecraft.getInstance().getMainRenderTarget();
        NativeImage image = Screenshot.takeScreenshot(target);
        try {
            int width = x2 - x1;
            int height = y2 - y1;
            BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            for (int row = 0; row < height; row++) {
                for (int column = 0; column < width; column++) {
                    // NativeImage stores pixels as ABGR (so they upload to GL as RGBA).
                    int pixel = image.getPixelRGBA(x1 + column, y1 + row);
                    int r = pixel & 0xFF;
                    int g = (pixel >> 8) & 0xFF;
                    int b = (pixel >> 16) & 0xFF;
                    out.setRGB(column, row, (r << 16) | (g << 8) | b);
                }
            }
            return out;
        } finally {
            image.close();
        }
    }

    /** The client (render) thread, noted by the client tick, which is what starts every test. */
    private static volatile Thread clientThread;

    /** Called on the client thread at the start of each client tick. */
    @GrugGenerated("client-thread marshalling: only a running client has a client thread")
    static void noteClientThread() {
        clientThread = Thread.currentThread();
    }

    /**
     * Runs a task on the client (render) thread, blocking until it has finished.
     *
     * <p>The waiting thread is inside a grug call, and the task can enter the state itself (a block
     * use, a runtime error report), so the wait releases the state lock: the blocked thread is not
     * running grug code, and the task is then the one active entry.
     *
     * <p>Only the client thread may enter the state meanwhile. A chunk worker that drew a block
     * entity there would report its err into the suspended call, which the engine then stops for an
     * err that is not its own: the render-error race of #583 and #836. The worker waits, holding
     * nothing, and draws once the call has its lock back.
     */
    @GrugGenerated("client-thread marshalling: the same-thread path cannot occur in a CI run")
    private static void onClientThread(Runnable task) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.isSameThread()) {
            task.run();
            return;
        }
        CountDownLatch latch = new CountDownLatch(1);
        mc.execute(
                () -> {
                    try {
                        task.run();
                    } finally {
                        latch.countDown();
                    }
                });
        // One lambda for both arms: JaCoCo measures a lambda as its own method, and the method's
        // own @GrugGenerated does not carry over to it, so a second lambda in an arm no run takes
        // would be counted and miss the gate. See #1086.
        Runnable wait = () -> awaitLatch(latch);
        Thread admitted = clientThread;
        if (admitted == null) {
            Grug.runWithStateLockReleased(wait);
        } else {
            Grug.runWithStateLockReleased(wait, admitted);
        }
    }

    /**
     * Awaits the released client-thread task. The interrupt path only runs when the game shuts down
     * mid-wait, which a CI run does not do.
     */
    @GrugGenerated("client-thread marshalling: an interrupted wait cannot occur in a CI run")
    private static void awaitLatch(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
