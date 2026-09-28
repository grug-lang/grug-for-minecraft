package net.grug.minecraft.forge;

import com.mojang.logging.LogUtils;

import net.grug.minecraft.core.GrugCore;
import net.grug.minecraft.core.GrugTestRunner;
import net.grug.minecraft.forge.block.GrugBlock;
import net.grug.minecraft.forge.block.entity.GrugBlockEntity;
import net.grug.minecraft.forge.gui.GrugMenu;
import net.grug.minecraft.forge.gui.GrugScreen;
import net.grug.minecraft.forge.resource.GrugPackResources;
import net.grug.minecraft.grug.FileInfo;
import net.grug.minecraft.grug.Grug;
import net.grug.minecraft.grug.GrugBlockData;
import net.grug.minecraft.grug.GrugFileIndex;
import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.grug.GrugItemData;
import net.grug.minecraft.grug.GrugScreenshots;
import net.grug.minecraft.gui.GrugGuiBuilder;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
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
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Mod(GrugModLoader.MODID)
public class GrugModLoader {
    public static final String MODID = "grug";
    public static final Logger LOGGER = LogUtils.getLogger();

    /** Whether a screenshot test run (the R hotkey or CI) is currently active. */
    public static boolean isTestRunActive() {
        return ClientForgeEvents.grug$testRunner != null;
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
                                                (net.minecraft.world.Container)
                                                        inv.player.level().getBlockEntity(pos);
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

        GrugCore.initialize(new ForgeAdapter(), modApiJson, activeGrugDir);

        FileInfo[] files = Grug.compileAllFiles();

        for (GrugFileIndex.Entry entry : GrugFileIndex.classify(files)) {
            FileInfo file = entry.file();
            Grug.fileIds.put(file.path(), file.fileId());

            switch (entry.entityType()) {
                case "Block" -> blockFiles.put(entry.cleanName(), file.fileId());
                case "BlockEntity" ->
                        Grug.entityFileIdsByName.put(entry.cleanName(), file.fileId());
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
            Grug.currentlyInitializingBlock = blockData;

            long tempEntityHandle = Grug.createEntity(blockFileId);
            long initFnId = Grug.getExportFnId("Block", "init");

            if (tempEntityHandle != 0 && initFnId != Grug.INVALID_GRUG_EXPORT_FN_ID) {
                Grug.callExportFn(tempEntityHandle, initFnId);
            }

            if (tempEntityHandle != 0) {
                Grug.destroyEntity(tempEntityHandle);
            }

            Grug.declaredBlocks.put(blockData.id, blockData);
            Grug.blockDataByFileId.put(blockFileId, blockData);
            Grug.currentlyInitializingBlock = null;

            var blockReg =
                    BLOCKS.register(
                            cleanName,
                            () ->
                                    new GrugBlock(
                                            BlockBehaviour.Properties.of()
                                                    .mapColor(MapColor.STONE)
                                                    .strength(blockData.hardness),
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

            Grug.declaredItems.put(itemData.id, itemData);
            Grug.itemDataByFileId.put(itemFileId, itemData);

            var itemReg = ITEMS.register(cleanName, () -> new Item(new Item.Properties()));
            registeredGrugItems.add(itemReg);
        }
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            String[] updatedResources =
                    Grug.update(
                            errorMsg -> {
                                LOGGER.error(errorMsg);
                                synchronized (Grug.runtimeErrorQueue) {
                                    Grug.runtimeErrorQueue.add(errorMsg);
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
    }

    @Mod.EventBusSubscriber(
            modid = MODID,
            bus = Mod.EventBusSubscriber.Bus.FORGE,
            value = Dist.CLIENT)
    public static class ClientForgeEvents {

        private static boolean grug$titleSet = false;
        private static boolean grug$testsRan = false;
        private static GrugTestRunner grug$testRunner = null;
        private static boolean grug$testRunnerFromCI = false;

        private static boolean grug$resolutionForced = false;
        private static boolean grug$resolutionForcedByTestRun = false;
        private static int grug$savedWindowWidth;
        private static int grug$savedWindowHeight;
        private static int grug$lastCursorX = Integer.MIN_VALUE;
        private static int grug$lastCursorY = Integer.MIN_VALUE;
        private static double grug$savedCursorX;
        private static double grug$savedCursorY;
        private static boolean grug$cursorParked = false;
        private static volatile boolean grug$testRunFinished = false;

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
                    grug$testsRan = true;
                    startTestRunner(mc, true);
                }

                // The runner does one Test.run() call per real tick, so that real ticks (and real
                // rendered frames) elapse between a test's own invocations.
                advanceTestRunner(mc);

                // The run finishes on the server thread; restoring the window has to happen here on
                // the client thread.
                if (grug$testRunFinished) {
                    grug$testRunFinished = false;
                    finishTestRun(mc);
                }

                updateCursorReadout(mc);

                // A captured frame must not depend on where the invisible cursor happens to be: the
                // game highlights the slot under the mouse and tooltips follow it, so park the
                // cursor in a corner outside the centered GUI for the duration of a run. The cursor
                // isn't drawn into the framebuffer, so this only removes that incidental state.
                if (grug$testRunner != null && mc.screen != null) {
                    parkCursor(mc);
                }

                // Intercept the flag from the server tick thread
                // to trigger a client-side reload
                if (GrugModLoader.reloadClientResources) {
                    GrugModLoader.reloadClientResources = false;

                    // Trigger the reload and immediately clear the LoadingOverlay it creates
                    mc.reloadResourcePacks();
                    mc.setOverlay(null);
                }

                // Process standard message queues directly in the local player's chat
                if (mc.player != null) {
                    synchronized (Grug.runtimeErrorQueue) {
                        while (!Grug.runtimeErrorQueue.isEmpty()) {
                            sendRedMessage(mc.player, Grug.runtimeErrorQueue.poll());
                        }
                    }

                    synchronized (Grug.printQueue) {
                        while (!Grug.printQueue.isEmpty()) {
                            sendMessage(mc.player, Grug.printQueue.poll(), "");
                        }
                    }
                }
            }
        }

        @GrugGenerated("dev-only: R runs tests and M forces the test resolution")
        private static void handleDevHotkeys(Minecraft mc) {
            while (RUN_TESTS_KEY.consumeClick()) {
                startTestRunner(mc, false);
            }
            while (FORCE_RESOLUTION_KEY.consumeClick()) {
                toggleResolution(mc);
            }
        }

        @GrugGenerated("dev-only: cursor-coordinate readout while M holds the test resolution")
        private static void updateCursorReadout(Minecraft mc) {
            if (grug$resolutionForced && grug$testRunner == null) {
                updateCursorPositionReadout(mc);
            } else {
                grug$lastCursorX = Integer.MIN_VALUE;
                grug$lastCursorY = Integer.MIN_VALUE;
            }
        }

        private static void startTestRunner(Minecraft mc, boolean fromCI) {
            // Pressing the hotkey again while a run is still going shouldn't restart it.
            if (grug$testRunner != null) {
                return;
            }
            // Screenshot tests only compare equal at 1280x720. If M already forced that size, leave
            // its state alone: this run didn't set it up, so it must not undo it.
            if (!grug$resolutionForced) {
                saveAndForceResolution(mc);
                grug$resolutionForcedByTestRun = true;
            }
            grug$testRunner = new GrugTestRunner();
            grug$testRunnerFromCI = fromCI;
            advanceTestRunner(mc);
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

        /** M: toggle the window between its current size and the test resolution. */
        @GrugGenerated("dev-only: M forces the test resolution by hand")
        private static void toggleResolution(Minecraft mc) {
            if (!grug$resolutionForced) {
                saveAndForceResolution(mc);
                grug$resolutionForcedByTestRun = false;
            } else {
                grug$resolutionForced = false;
                grug$resolutionForcedByTestRun = false;
                applyWindowSize(mc, grug$savedWindowWidth, grug$savedWindowHeight);
            }
        }

        private static void saveAndForceResolution(Minecraft mc) {
            grug$savedWindowWidth = mc.getWindow().getScreenWidth();
            grug$savedWindowHeight = mc.getWindow().getScreenHeight();
            grug$resolutionForced = true;
            applyWindowSize(mc, GrugScreenshots.WIDTH, GrugScreenshots.HEIGHT);
        }

        private static void finishTestRun(Minecraft mc) {
            if (grug$resolutionForcedByTestRun) {
                grug$resolutionForced = false;
                grug$resolutionForcedByTestRun = false;
                applyWindowSize(mc, grug$savedWindowWidth, grug$savedWindowHeight);
            }
            if (grug$cursorParked) {
                grug$cursorParked = false;
                GLFW.glfwSetCursorPos(
                        mc.getWindow().getWindow(), grug$savedCursorX, grug$savedCursorY);
            }
        }

        /**
         * Moves the cursor to the window's top-left corner while a screen is open, so a screenshot
         * doesn't record the slot-hover highlight. GLFW cursor coordinates are window pixels with
         * the origin at the top left, and the corner is outside every centered GUI panel.
         */
        @GrugGenerated("dev-only: cursor parking")
        private static void parkCursor(Minecraft mc) {
            long window = mc.getWindow().getWindow();
            double[] x = new double[1];
            double[] y = new double[1];
            GLFW.glfwGetCursorPos(window, x, y);
            if (!grug$cursorParked) {
                grug$savedCursorX = x[0];
                grug$savedCursorY = y[0];
                grug$cursorParked = true;
            }
            if (x[0] != 0.0 || y[0] != 0.0) {
                GLFW.glfwSetCursorPos(window, 0.0, 0.0);
            }
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
}
