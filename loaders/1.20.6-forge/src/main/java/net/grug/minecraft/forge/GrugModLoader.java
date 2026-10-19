package net.grug.minecraft.forge;

import com.mojang.logging.LogUtils;

import net.grug.minecraft.core.GrugCore;
import net.grug.minecraft.core.GrugRunWindow;
import net.grug.minecraft.core.GrugTestRunner;
import net.grug.minecraft.forge.block.GrugBakedModel;
import net.grug.minecraft.forge.block.GrugBlock;
import net.grug.minecraft.forge.block.GrugItemBakedModel;
import net.grug.minecraft.forge.block.entity.GrugBlockEntity;
import net.grug.minecraft.forge.gui.GrugMenu;
import net.grug.minecraft.forge.gui.GrugScreen;
import net.grug.minecraft.forge.resource.GrugPackResources;
import net.grug.minecraft.grug.FileInfo;
import net.grug.minecraft.grug.Grug;
import net.grug.minecraft.grug.GrugBlockData;
import net.grug.minecraft.grug.GrugDeclarations;
import net.grug.minecraft.grug.GrugEntities;
import net.grug.minecraft.grug.GrugErrors;
import net.grug.minecraft.grug.GrugFileIndex;
import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.grug.GrugItemData;
import net.grug.minecraft.grug.GrugModsWatcher;
import net.grug.minecraft.grug.GrugReference;
import net.grug.minecraft.grug.GrugScreenshots;
import net.grug.minecraft.gui.GrugGuiBuilder;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.client.tutorial.TutorialSteps;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.ReloadableResourceManager;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ModelEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.event.AddPackFindersEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLLoader;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

@Mod(GrugModLoader.MODID)
public class GrugModLoader {
    public static final String MODID = "grug";
    public static final Logger LOGGER = LogUtils.getLogger();

    /** Whether a screenshot test run (the R hotkey or CI) is currently active. */
    public static boolean isTestRunActive() {
        return ClientForgeEvents.grug$testRunner != null;
    }

    /**
     * The section buffer pool's size, recorded by {@link SectionBufferBuilderPoolMixin} when the
     * pool is built, or 0 before that.
     *
     * <p>Each section compile a worker is running holds one of the pool's buffers, so a free count
     * below this size means a compile is in flight. The settle answer needs that phase: {@code
     * LevelRenderer.hasRenderedAllSections}, the queue check alone it used to rest on, counts
     * queued and finished compiles only, so it can say yes while a worker is still compiling the
     * section a capture is about to read. It is volatile because the mixin writes it on the client
     * thread while the settle answer reads it on the integrated server thread: the write happens
     * before that thread starts today, but nothing in the code says the pool is only built once,
     * and a stale 0 would never let the wait succeed. See #259.
     */
    private static volatile int sectionBufferPoolSize = 0;

    public static void recordSectionBufferPoolSize(int size) {
        sectionBufferPoolSize = size;
    }

    public static int getSectionBufferPoolSize() {
        return sectionBufferPoolSize;
    }

    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, MODID);
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, MODID);
    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(ForgeRegistries.MENU_TYPES, MODID);
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);

    private static final Map<String, Long> blockFiles = new HashMap<>();
    private static final Map<String, Long> itemFiles = new HashMap<>();
    private static final List<RegistryObject<GrugBlock>> registeredGrugBlocks = new ArrayList<>();
    private static final List<RegistryObject<? extends Item>> registeredGrugItems =
            new ArrayList<>();

    public static volatile boolean reloadClientResources = false;

    /**
     * The batches of reported paths that reloads were started for, oldest first, and the batches
     * whose apply stage has finished but whose frame has not been rendered yet.
     *
     * <p>{@link GrugReloadListener} takes the oldest batch when a reload starts and adds it to the
     * applied queue once that reload's apply stage has run; the render tick's end then reports
     * every applied batch to {@link Grug}, so a test that waits on reload_count captures a frame
     * the reload is in. A batch belongs to the reload that was started for it, not to whichever
     * reload happens to finish, because reloads overlap here. Both queues are only touched on the
     * client thread.
     */
    private static final Deque<String[]> reloadBatches = new ArrayDeque<>();

    private static final Deque<String[]> appliedReloadBatches = new ArrayDeque<>();

    /**
     * Reloads the client has started and not yet reported finished. Raised where a reload is
     * started and lowered where its finish is reported, so it covers the whole reload, including
     * the render-tick wait between its apply stage and the report. Read from the test thread.
     */
    private static final AtomicInteger reloadsOutstanding = new AtomicInteger();

    /**
     * Whether a resource reload is waiting to start or has not finished, so a frame drawn now is
     * not one a test can settle on: the render thread spends the reload stitching atlases instead
     * of processing what the server sent, and frames drawn meanwhile end idle because the client
     * has not yet seen the test's own setup. See #328.
     */
    public static boolean isReloading() {
        return reloadClientResources || reloadsOutstanding.get() > 0;
    }

    /**
     * Called by {@link GrugReloadListener} as its reload starts; empty when no batch was queued.
     */
    static String[] pollReloadBatch() {
        return reloadBatches.isEmpty() ? new String[0] : reloadBatches.pollFirst();
    }

    /** Called by {@link GrugReloadListener} once a reload's apply stage has finished. */
    static void markReloadApplied(String[] batch) {
        appliedReloadBatches.addLast(batch);
    }

    public static final KeyMapping RUN_TESTS_KEY =
            new KeyMapping("key.grug.run_tests", GLFW.GLFW_KEY_R, "key.categories.grug");

    public static final KeyMapping FORCE_RESOLUTION_KEY =
            new KeyMapping(
                    "key.grug.force_test_resolution", GLFW.GLFW_KEY_M, "key.categories.grug");

    public static final RegistryObject<MenuType<GrugMenu>> GRUG_MENU =
            MENUS.register(
                    "grug_menu",
                    () ->
                            IForgeMenuType.create(
                                    (windowId, inv, data) -> {
                                        net.minecraft.core.BlockPos pos = data.readBlockPos();
                                        GrugGuiBuilder builder = GrugMenu.readBuilder(data);
                                        net.minecraft.world.Container container =
                                                GrugMenu.clientContainer(
                                                        inv.player.level().getBlockEntity(pos),
                                                        builder);
                                        return new GrugMenu(
                                                null, windowId, inv, container, builder);
                                    }));

    public static final RegistryObject<CreativeModeTab> GRUG_TAB =
            CREATIVE_MODE_TABS.register(
                    "grug_tab",
                    () ->
                            CreativeModeTab.builder()
                                    .title(Component.literal("Grug Mods"))
                                    .icon(
                                            () ->
                                                    !registeredGrugItems.isEmpty()
                                                            ? new ItemStack(
                                                                    registeredGrugItems
                                                                            .get(0)
                                                                            .get())
                                                            : new ItemStack(Blocks.STONE))
                                    .displayItems(
                                            (parameters, output) -> {
                                                for (var itemReg : registeredGrugItems) {
                                                    output.accept(itemReg.get());
                                                }
                                            })
                                    .build());

    public static RegistryObject<BlockEntityType<GrugBlockEntity>> GRUG_BLOCK_ENTITY;

    public GrugModLoader() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

        initializeGrug();

        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        MENUS.register(modEventBus);
        CREATIVE_MODE_TABS.register(modEventBus);

        GRUG_BLOCK_ENTITY =
                BLOCK_ENTITIES.register(
                        "grug_block_entity",
                        () -> {
                            Block[] blockArray =
                                    registeredGrugBlocks.stream()
                                            .map(RegistryObject::get)
                                            .toArray(Block[]::new);
                            return BlockEntityType.Builder.of(GrugBlockEntity::new, blockArray)
                                    .build(null);
                        });
        BLOCK_ENTITIES.register(modEventBus);

        modEventBus.addListener(this::onAddPackFinders);

        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(this);
    }

    @GrugGenerated("non-dev-mode: only reached when not running from a dev mods directory")
    public static File getActiveGrugModsDir() {
        File gameDir = FMLLoader.getGamePath().toFile();

        File override = GrugReference.modsDirOverride();
        if (override != null) {
            if (!override.isDirectory()) {
                throw Grug.fatal("GRUG_MODS_DIR is not a directory: " + override);
            }
            return override;
        }

        if (!FMLEnvironment.production) {
            File devGrugDir = new File(gameDir, "../../../mods");
            if (devGrugDir.exists() && devGrugDir.isDirectory()) {
                return devGrugDir;
            }
        }
        return new File(gameDir, "grug_mods");
    }

    private void onAddPackFinders(AddPackFindersEvent event) {
        PackType type = event.getPackType();
        PackLocationInfo info =
                new PackLocationInfo(
                        "grug_resources_" + type.name(),
                        Component.literal(
                                "Grug Mod "
                                        + (type == PackType.CLIENT_RESOURCES
                                                ? "Resources"
                                                : "Data")),
                        PackSource.BUILT_IN,
                        Optional.empty());
        Pack pack =
                Pack.readMetaAndCreate(
                        info,
                        new Pack.ResourcesSupplier() {
                            @Override
                            public PackResources openPrimary(PackLocationInfo locationInfo) {
                                return new GrugPackResources(locationInfo);
                            }

                            @Override
                            public PackResources openFull(
                                    PackLocationInfo locationInfo, Pack.Metadata metadata) {
                                return openPrimary(locationInfo);
                            }
                        },
                        type,
                        new PackSelectionConfig(true, Pack.Position.TOP, false));
        if (pack != null) {
            event.addRepositorySource(consumer -> consumer.accept(pack));
        }
    }

    private void initializeGrug() {
        File gameDir = FMLLoader.getGamePath().toFile();
        File runGrugDir = new File(gameDir, "grug_mods");

        if (!runGrugDir.exists()) {
            runGrugDir.mkdirs();
        }

        File modApiJson = new File(runGrugDir, "mod_api.json");

        try (InputStream in = GrugCore.class.getResourceAsStream("/mod_api.json")) {
            if (in == null) {
                throw Grug.fatal("/mod_api.json is missing from the GrugCore jar");
            }
            Files.copy(in, modApiJson.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw Grug.fatal("Failed to copy mod_api.json", e);
        }

        File activeGrugDir = getActiveGrugModsDir();

        // mods.toml declares this mod BOTH, so this class runs on a client and on a dedicated
        // server, and the adapter that answers for the level and player handles differs between
        // them. See #162.
        GrugCore.initialize(ForgeAdapter.forCurrentEnvironment(), modApiJson, activeGrugDir);

        FileInfo[] files = Grug.compileAllFiles();

        for (GrugFileIndex.Entry entry : GrugFileIndex.classify(files)) {
            FileInfo file = entry.file();
            GrugModsWatcher.fileIds.put(file.path(), file.fileId());

            switch (entry.entityType()) {
                case "Block" -> blockFiles.put(entry.cleanName(), file.fileId());
                case "BlockEntity" ->
                        GrugEntities.entityFileIdsByName.put(entry.cleanName(), file.fileId());
                case "Item" -> itemFiles.put(entry.cleanName(), file.fileId());
                default -> {}
            }
        }

        registerDiscoveredBlocksAndItems();
    }

    private void registerDiscoveredBlocksAndItems() {
        for (Map.Entry<String, Long> entry : blockFiles.entrySet()) {
            String cleanName = entry.getKey();
            long blockFileId = entry.getValue();

            GrugBlockData blockData = new GrugBlockData(MODID + ":" + cleanName);
            GrugDeclarations.currentlyInitializingBlock = blockData;

            long tempEntityHandle = Grug.createEntity(blockFileId);
            long initFnId = Grug.getExportFnId("Block", "init");

            if (tempEntityHandle != 0 && initFnId != Grug.INVALID_GRUG_EXPORT_FN_ID) {
                Grug.callExportFn(tempEntityHandle, initFnId);
            }

            if (tempEntityHandle != 0) {
                Grug.destroyEntity(tempEntityHandle);
            }

            GrugDeclarations.declaredBlocks.put(blockData.id, blockData);
            GrugDeclarations.blockDataByFileId.put(blockFileId, blockData);
            GrugDeclarations.currentlyInitializingBlock = null;

            var blockReg =
                    BLOCKS.register(
                            cleanName,
                            () ->
                                    new GrugBlock(
                                            customRenderProperties(
                                                    BlockBehaviour.Properties.of()
                                                            .mapColor(MapColor.STONE)
                                                            .strength(blockData.hardness),
                                                    blockData),
                                            blockFileId));
            registeredGrugBlocks.add(blockReg);

            var itemReg =
                    ITEMS.register(
                            cleanName, () -> new BlockItem(blockReg.get(), new Item.Properties()));
            registeredGrugItems.add(itemReg);
        }

        for (Map.Entry<String, Long> entry : itemFiles.entrySet()) {
            String cleanName = entry.getKey();
            long itemFileId = entry.getValue();

            GrugItemData itemData = new GrugItemData(MODID + ":" + cleanName);

            long tempEntityHandle = Grug.createEntity(itemFileId);
            long initFnId = Grug.getExportFnId("Item", "init");

            if (tempEntityHandle != 0 && initFnId != Grug.INVALID_GRUG_EXPORT_FN_ID) {
                Grug.callExportFn(tempEntityHandle, initFnId);
            }

            if (tempEntityHandle != 0) {
                Grug.destroyEntity(tempEntityHandle);
            }

            GrugDeclarations.declaredItems.put(itemData.id, itemData);
            GrugDeclarations.itemDataByFileId.put(itemFileId, itemData);

            var itemReg = ITEMS.register(cleanName, () -> new Item(new Item.Properties()));
            registeredGrugItems.add(itemReg);
        }
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            String[] updatedResources =
                    GrugModsWatcher.update(
                            errorMsg -> {
                                // A contained mod err from the hot-reload path: the file that
                                // failed to compile is reported to the player, and the run carries
                                // on with the version it last had, exactly as the JNI layer lets a
                                // script carry on after a host error.
                                LOGGER.error(errorMsg);
                                synchronized (GrugErrors.runtimeErrorQueue) {
                                    GrugErrors.runtimeErrorQueue.add(errorMsg);
                                }
                            });
            for (String resource : updatedResources) {
                LOGGER.info("Reloading changed resource: {}", resource);
                reloadClientResources = true;
            }
        }
    }

    @Mod.EventBusSubscriber(
            modid = MODID,
            bus = Mod.EventBusSubscriber.Bus.MOD,
            value = Dist.CLIENT)
    public static class ClientModEvents {
        @SubscribeEvent
        public static void onClientSetup(FMLClientSetupEvent event) {
            event.enqueueWork(
                    () -> {
                        MenuScreens.register(
                                GRUG_MENU.get(),
                                (GrugMenu menu, Inventory inv, Component title) ->
                                        new GrugScreen(menu, inv, title, menu.layout));
                    });
        }

        @SubscribeEvent
        public static void onKeyRegister(RegisterKeyMappingsEvent event) {
            event.register(RUN_TESTS_KEY);
            event.register(FORCE_RESOLUTION_KEY);
        }

        /**
         * Replaces the baked model of every block that declared custom rendering with one that
         * draws its block entity's geometry instead of its cube.
         *
         * <p>The map is keyed by the model location the blockstate resolves to, which for a block
         * state is the block's registry name and its properties rather than the model file the
         * blockstate names, so the lookup does not depend on how a mod's blockstate JSON points at
         * its model. The original model stays inside the wrapper, which is where the sprite of each
         * face and the particle icon still come from.
         *
         * <p>A flat block's item model is wrapped too, at the location the item renderer looks it
         * up under, its registry name and the inventory variant, so its item form is drawn with a
         * block sheet rather than the shaded entity sheet. See {@link GrugItemBakedModel}.
         *
         * <p>This runs on a model-baking worker thread, so it touches only the registry and the
         * already compiled block data, which is what the event's own contract asks for.
         */
        @SubscribeEvent
        public static void onModifyBakingResult(ModelEvent.ModifyBakingResult event) {
            for (RegistryObject<GrugBlock> blockReg : registeredGrugBlocks) {
                GrugBlock block = blockReg.get();

                if (block.drawsFlatLighting()) {
                    ModelResourceLocation itemLocation =
                            new ModelResourceLocation(
                                    BuiltInRegistries.ITEM.getKey(block.asItem()), "inventory");
                    BakedModel itemModel = event.getModels().get(itemLocation);
                    if (itemModel != null) {
                        event.getModels().put(itemLocation, new GrugItemBakedModel(itemModel));
                    }
                }

                if (!block.drawsCustomGeometry()) continue;

                ModelResourceLocation location =
                        BlockModelShaper.stateToModelLocation(block.defaultBlockState());
                BakedModel original = event.getModels().get(location);
                if (original == null) continue;
                event.getModels().put(location, new GrugBakedModel(original));
            }
        }
    }

    @Mod.EventBusSubscriber(
            modid = MODID,
            bus = Mod.EventBusSubscriber.Bus.FORGE,
            value = Dist.CLIENT)
    public static class ClientForgeEvents {

        private static boolean grug$titleSet = false;
        private static boolean grug$testsRan = false;

        /**
         * How long to wait for the client to receive the world before failing the run. A placement
         * into a chunk that has not arrived is silently replaced later, so the wait is what keeps a
         * setup from building into a placeholder chunk; the deadline is what keeps a world that
         * never loads from hanging.
         */
        private static final long WORLD_READY_TIMEOUT_MILLIS = 60000;

        private static long grug$worldWaitStartMillis = 0;

        private static GrugTestRunner grug$testRunner = null;
        private static boolean grug$testRunnerFromCI = false;

        private static int grug$lastCursorX = Integer.MIN_VALUE;
        private static int grug$lastCursorY = Integer.MIN_VALUE;
        private static volatile boolean grug$testRunFinished = false;

        /**
         * Whether {@link GrugReloadListener} has been registered with the client's resource
         * manager. Registered on the first client tick, once the manager exists, so every reload
         * this loader starts from then on reports its finish to {@link Grug}.
         */
        private static boolean grug$reloadListenerRegistered = false;

        /**
         * The 1.20.6 half of the shared run state machine: the GLFW side of resizing the window and
         * moving the cursor. 1.20.6 never managed GL dithering, so that half is inert here and a
         * run behaves exactly as it did before. The save/force/restore transitions themselves live
         * in core's GrugRunWindow, which is where they are measured.
         */
        private static final GrugRunWindow.Display DISPLAY =
                new GrugRunWindow.Display() {
                    @Override
                    public int width() {
                        return Minecraft.getInstance().getWindow().getScreenWidth();
                    }

                    @Override
                    public int height() {
                        return Minecraft.getInstance().getWindow().getScreenHeight();
                    }

                    @Override
                    public void setSize(int width, int height) {
                        applyWindowSize(Minecraft.getInstance(), width, height);
                    }

                    @Override
                    public boolean ditherOn() {
                        return false;
                    }

                    @Override
                    public void setDither(boolean on) {
                        // This loader does not touch GL dithering.
                    }

                    @Override
                    public double[] cursorPos() {
                        return readCursorPos();
                    }

                    @Override
                    public void setCursor(double x, double y) {
                        GLFW.glfwSetCursorPos(
                                Minecraft.getInstance().getWindow().getWindow(), x, y);
                    }
                };

        /** The R and M presses, consumed as the shared state machine polls them. */
        private static final GrugRunWindow.Keys KEYS =
                new GrugRunWindow.Keys() {
                    @Override
                    public boolean runPressed() {
                        return RUN_TESTS_KEY.consumeClick();
                    }

                    @Override
                    public boolean forceResolutionPressed() {
                        return FORCE_RESOLUTION_KEY.consumeClick();
                    }
                };

        /** The shared state machine for the run's window and cursor, driven by the glue above. */
        private static final GrugRunWindow WINDOW = new GrugRunWindow(DISPLAY);

        /** The cursor in window pixels, the way this loader always read it. */
        private static double[] readCursorPos() {
            double[] x = new double[1];
            double[] y = new double[1];
            GLFW.glfwGetCursorPos(Minecraft.getInstance().getWindow().getWindow(), x, y);
            return new double[] {x[0], y[0]};
        }

        /**
         * Reports each finished reload once the frame that shows it has been rendered.
         *
         * <p>The listener's apply stage uploads the atlases before the frame is drawn, so reporting
         * there would let a test capture the previous frame, which still has the old resources in
         * it. The render tick's end runs after the frame, so the frame a test reads next is the one
         * the reload is in.
         */
        @SubscribeEvent
        public static void onRenderTick(TickEvent.RenderTickEvent event) {
            if (event.phase != TickEvent.Phase.END) return;
            while (!GrugModLoader.appliedReloadBatches.isEmpty()) {
                GrugModsWatcher.noteLoaderReloadFinished(
                        GrugModLoader.appliedReloadBatches.pollFirst());
                GrugModLoader.reloadsOutstanding.decrementAndGet();
            }
        }

        /**
         * One completed frame, for {@link ForgeAdapter#onFrameRendered}.
         *
         * <p>{@code AFTER_LEVEL} fires once per frame, after the level's sections were uploaded and
         * drawn, which is the moment a capture's frame rule waits for. It is deliberately not a
         * client-tick event: a tick is not a frame, and the queues can empty between two frames.
         */
        @SubscribeEvent
        public static void onRenderLevelStage(RenderLevelStageEvent event) {
            if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_LEVEL) {
                GrugCore.getAdapter().onFrameRendered();
            }
        }

        @SubscribeEvent
        public static void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase == TickEvent.Phase.START) {
                Minecraft mc = Minecraft.getInstance();

                if (mc.getWindow() != null && !grug$titleSet) {
                    mc.getWindow().setTitle("Minecraft 1.20.6 - Forge with grug");
                    grug$titleSet = true;

                    if ("true".equals(System.getenv("GRUG_CI"))) {
                        GrugModLoader.LOGGER.info("[GRUG CI] BOOT TO TITLE SCREEN SUCCESSFUL");

                        File savesDir = new File(mc.gameDirectory, "saves");
                        File[] saves = savesDir.listFiles(File::isDirectory);
                        if (saves != null && saves.length > 0) {
                            mc.createWorldOpenFlows()
                                    .openWorld(saves[0].getName(), () -> mc.setScreen(null));
                        }
                    }
                }

                handleDevHotkeys(mc);

                if ("true".equals(System.getenv("GRUG_CI"))
                        && !grug$testsRan
                        && mc.player != null
                        && mc.getSingleplayerServer() != null) {
                    // The runner starts as soon as the player exists, which can be before the
                    // client
                    // has received the chunks the tests build in. Wait for them; a world that never
                    // arrives fails the run instead of starting tests against placeholder chunks.
                    if (grug$worldWaitStartMillis == 0) {
                        grug$worldWaitStartMillis = System.currentTimeMillis();
                    }
                    boolean ready = GrugCore.getAdapter().isWorldReady(mc.player);
                    if (ready) {
                        System.out.println(
                                "[GRUG CI] CI mode active & player loaded after "
                                        + (System.currentTimeMillis() - grug$worldWaitStartMillis)
                                        + " ms. Firing tests!");
                        grug$testsRan = true;
                        if (WINDOW.beginRun()) {
                            stopTutorialForRun(mc);
                            shrinkViewForRun(mc);
                            grug$testRunner = new GrugTestRunner();
                            grug$testRunnerFromCI = true;
                            advanceTestRunner(mc);
                        }
                    } else if (System.currentTimeMillis() - grug$worldWaitStartMillis
                            >= WORLD_READY_TIMEOUT_MILLIS) {
                        System.out.println("[GRUG CI] FAIL world readiness");
                        System.out.println(
                                "[GRUG CI] The client did not receive the world within "
                                        + (WORLD_READY_TIMEOUT_MILLIS / 1000)
                                        + " seconds, so the tests were not started.");
                        grug$testsRan = true;
                    }
                }

                // The runner does one Test.run() call per real tick, so that real ticks (and real
                // rendered frames) elapse between a test's own invocations.
                advanceTestRunner(mc);

                // The run finishes on the server thread; restoring the window has to happen here on
                // the client thread.
                if (grug$testRunFinished) {
                    grug$testRunFinished = false;
                    WINDOW.endRun();
                    restoreTutorialAfterRun(mc);
                    restoreViewAfterRun(mc);
                }

                updateCursorReadout(mc);

                // A captured frame must not depend on where the invisible cursor happens to be: the
                // game highlights the slot under the mouse and tooltips follow it, so park the
                // cursor in a corner outside the centered GUI for the duration of a run. The cursor
                // isn't drawn into the framebuffer, so this only removes that incidental state.
                if (grug$testRunner != null && mc.screen != null) {
                    WINDOW.parkCursor();
                }

                // Registered once the manager exists, which is before any hot reload, so every
                // reload the flag below starts reports its finish through GrugReloadListener.
                if (!grug$reloadListenerRegistered
                        && mc.getResourceManager() instanceof ReloadableResourceManager manager) {
                    grug$reloadListenerRegistered = true;
                    manager.registerReloadListener(new GrugReloadListener());
                }

                // Intercept the flag from the server tick thread
                // to trigger a client-side reload
                if (GrugModLoader.reloadClientResources) {
                    GrugModLoader.reloadClientResources = false;

                    // Trigger the reload and immediately clear the LoadingOverlay it creates. The
                    // overlay is what would complete the returned future, so the listener is the
                    // finish signal rather than that future.
                    GrugModLoader.reloadsOutstanding.incrementAndGet();
                    GrugModLoader.reloadBatches.addLast(GrugModsWatcher.takePendingReloadPaths());
                    mc.reloadResourcePacks();
                    mc.setOverlay(null);
                }

                // Process standard message queues directly in the local player's chat
                if (mc.player != null) {
                    synchronized (GrugErrors.runtimeErrorQueue) {
                        while (!GrugErrors.runtimeErrorQueue.isEmpty()) {
                            sendRedMessage(mc.player, GrugErrors.runtimeErrorQueue.poll());
                        }
                    }

                    synchronized (GrugErrors.printQueue) {
                        while (!GrugErrors.printQueue.isEmpty()) {
                            sendMessage(mc.player, GrugErrors.printQueue.poll(), "");
                        }
                    }
                }
            }
        }

        @GrugGenerated("dev-only: R runs tests and M forces the test resolution")
        private static void handleDevHotkeys(Minecraft mc) {
            // The polls consume the presses they report, so this has to run every tick a press is
            // meant to count. The tick it reports a run press in is the one that starts the run.
            if (WINDOW.tick(KEYS)) {
                stopTutorialForRun(mc);
                shrinkViewForRun(mc);
                grug$testRunner = new GrugTestRunner();
                grug$testRunnerFromCI = false;
                advanceTestRunner(mc);
            }
        }

        /** The tutorial step a run found, put back when the run ends. */
        private static TutorialSteps savedTutorialStep = null;

        /**
         * Stops the tutorial before a run starts, because its "Move with WASD" toast stays over the
         * world until the player moves and a run never moves them. Left alone, it appears about
         * five seconds into the run and sits over the right end of the coverage_render shape, which
         * is part of what the capture is compared against. Only the step is set: the tutorial's own
         * tick would recreate a stopped step from the option, so the option is what has to change,
         * and {@link #restoreTutorialAfterRun} puts back the step a run found.
         */
        @GrugGenerated("dev-only: the toast would land in a capture")
        private static void stopTutorialForRun(Minecraft mc) {
            if (savedTutorialStep == null) {
                savedTutorialStep = mc.options.tutorialStep;
                mc.getTutorial().setStep(TutorialSteps.NONE);
            }
        }

        /** Puts the tutorial step back, and only if a run changed it. */
        @GrugGenerated("dev-only: the toast would land in a capture")
        private static void restoreTutorialAfterRun(Minecraft mc) {
            if (savedTutorialStep != null) {
                mc.getTutorial().setStep(savedTutorialStep);
                savedTutorialStep = null;
            }
        }

        /** The render distance a run found, put back when the run ends. */
        private static Integer savedRenderDistance = null;

        /**
         * Shrinks the view to the smallest the game allows before a run starts.
         *
         * <p>A run's room is 13 blocks across, so a default view only streams in far chunks that
         * the light engine and the section rebuilds then have to work through. On a
         * software-rendered CI runner that work outlasts the test's tick budget, so the wait for
         * the world to settle before a capture never finishes. See #253.
         */
        @GrugGenerated("dev-only: the view is shrunk for a run")
        private static void shrinkViewForRun(Minecraft mc) {
            if (savedRenderDistance == null) {
                savedRenderDistance = mc.options.renderDistance().get();
                mc.options.renderDistance().set(2);
            }
        }

        /** Puts the render distance back, and only if a run changed it. */
        @GrugGenerated("dev-only: the view is shrunk for a run")
        private static void restoreViewAfterRun(Minecraft mc) {
            if (savedRenderDistance != null) {
                mc.options.renderDistance().set(savedRenderDistance);
                savedRenderDistance = null;
            }
        }

        @GrugGenerated("dev-only: cursor-coordinate readout while M holds the test resolution")
        private static void updateCursorReadout(Minecraft mc) {
            if (WINDOW.resolutionForced() && grug$testRunner == null) {
                updateCursorPositionReadout(mc);
            } else {
                grug$lastCursorX = Integer.MIN_VALUE;
                grug$lastCursorY = Integer.MIN_VALUE;
            }
        }

        private static void advanceTestRunner(Minecraft mc) {
            GrugTestRunner runner = grug$testRunner;
            if (runner == null || mc.getSingleplayerServer() == null || mc.player == null) {
                return;
            }
            // Push the execution onto the server thread to prevent ghost blocks & crashes. The
            // whole
            // advance happens there, so the runner's state is only ever touched by one thread.
            mc.getSingleplayerServer()
                    .execute(
                            () -> {
                                Player serverPlayer =
                                        mc.getSingleplayerServer()
                                                .getPlayerList()
                                                .getPlayer(mc.player.getUUID());
                                if (serverPlayer == null) {
                                    return;
                                }
                                // Tasks queued before an earlier one finished the run are stale;
                                // the runner has
                                // already been cleared, so skip them rather than dereferencing a
                                // null.
                                if (grug$testRunner != runner) {
                                    return;
                                }
                                runner.tick(serverPlayer);
                                if (runner.isFinished()) {
                                    boolean fromCI = grug$testRunnerFromCI;
                                    grug$testRunner = null;
                                    grug$testRunnerFromCI = false;
                                    grug$testRunFinished = true;
                                    // The hotkey path leaves the game running so another R press
                                    // can start a fresh run.
                                    if (fromCI) {
                                        mc.stop();
                                    }
                                }
                            });
        }

        private static void applyWindowSize(Minecraft mc, int width, int height) {
            mc.getWindow().setWindowed(width, height);
            // The framebuffer fields only catch up when the GLFW callback is pumped, and the
            // screenshot size check reads them immediately, so set them explicitly too.
            mc.getWindow().setWidth(width);
            mc.getWindow().setHeight(height);
            mc.resizeDisplay();
        }

        /**
         * Prints the cursor position in the screenshot-crop convention (origin top left,
         * framebuffer pixels). Only prints when it changed, and only while a screen is open and the
         * window is at the test resolution.
         */
        @GrugGenerated("dev-only: cursor-coordinate readout")
        private static void updateCursorPositionReadout(Minecraft mc) {
            if (mc.getWindow().getWidth() != GrugScreenshots.WIDTH
                    || mc.getWindow().getHeight() != GrugScreenshots.HEIGHT
                    || mc.screen == null
                    || mc.getWindow().getScreenWidth() == 0
                    || mc.getWindow().getScreenHeight() == 0) {
                grug$lastCursorX = Integer.MIN_VALUE;
                grug$lastCursorY = Integer.MIN_VALUE;
                return;
            }

            double[] cursorX = new double[1];
            double[] cursorY = new double[1];
            GLFW.glfwGetCursorPos(mc.getWindow().getWindow(), cursorX, cursorY);

            int screenX =
                    (int)
                            Math.round(
                                    cursorX[0]
                                            * mc.getWindow().getWidth()
                                            / (double) mc.getWindow().getScreenWidth());
            int screenY =
                    (int)
                            Math.round(
                                    cursorY[0]
                                            * mc.getWindow().getHeight()
                                            / (double) mc.getWindow().getScreenHeight());

            if (screenX == grug$lastCursorX && screenY == grug$lastCursorY) {
                return;
            }
            grug$lastCursorX = screenX;
            grug$lastCursorY = screenY;
            sendMessage(mc.player, "Cursor: " + screenX + ", " + screenY, "");
        }

        private static void sendRedMessage(Player player, String text) {
            sendMessage(player, text, "\u00A7c");
        }

        private static void sendMessage(Player player, String text, String prefix) {
            if (player == null || text == null) return;

            String[] lines = text.split("\n");
            for (String line : lines) {
                LOGGER.info(prefix + line);

                // A CI run is not watched, and the readout is drawn into every capture that reaches
                // down to it, so the log is all it gets. See #412.
                if (GrugReference.isCiRun()) {
                    continue;
                }

                int maxLength = 50;
                while (line.length() > maxLength) {
                    int splitIndex = line.lastIndexOf(' ', maxLength);
                    if (splitIndex == -1) {
                        splitIndex = maxLength;
                    }
                    player.sendSystemMessage(
                            Component.literal(prefix + line.substring(0, splitIndex)));
                    line = line.substring(splitIndex).trim();
                }
                if (!line.isEmpty()) {
                    player.sendSystemMessage(Component.literal(prefix + line));
                }
            }
        }
    }

    /**
     * The properties of a block that draws its own geometry: it is not a full cube, so it does not
     * occlude. The game reads this once, when the block is built, so a hot reload that changes it
     * takes effect on the next start.
     */
    private static BlockBehaviour.Properties customRenderProperties(
            BlockBehaviour.Properties properties, GrugBlockData blockData) {
        return blockData.customRender ? properties.noOcclusion() : properties;
    }
}
