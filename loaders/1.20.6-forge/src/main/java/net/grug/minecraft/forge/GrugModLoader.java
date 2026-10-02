package net.grug.minecraft.forge;

import com.mojang.logging.LogUtils;

import net.grug.minecraft.core.GrugCore;
import net.grug.minecraft.core.GrugRunWindow;
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
import net.grug.minecraft.grug.GrugReference;
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
                    public int cursorX() {
                        return (int) cursorPos()[0];
                    }

                    @Override
                    public int cursorY() {
                        return (int) cursorPos()[1];
                    }

                    @Override
                    public void setCursor(int x, int y) {
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
        private static double[] cursorPos() {
            double[] x = new double[1];
            double[] y = new double[1];
            GLFW.glfwGetCursorPos(Minecraft.getInstance().getWindow().getWindow(), x, y);
            return new double[] {Math.round(x[0]), Math.round(y[0])};
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
                }

                updateCursorReadout(mc);

                // A captured frame must not depend on where the invisible cursor happens to be: the
                // game highlights the slot under the mouse and tooltips follow it, so park the
                // cursor in a corner outside the centered GUI for the duration of a run. The cursor
                // isn't drawn into the framebuffer, so this only removes that incidental state.
                if (grug$testRunner != null && mc.screen != null) {
                    WINDOW.parkCursor();
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
            // The polls consume the presses they report, so this has to run every tick a press is
            // meant to count. The tick it reports a run press in is the one that starts the run.
            if (WINDOW.tick(KEYS)) {
                grug$testRunner = new GrugTestRunner();
                grug$testRunnerFromCI = false;
                advanceTestRunner(mc);
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
