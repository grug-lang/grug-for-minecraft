package net.grug.minecraft.forge;

import com.mojang.logging.LogUtils;
import net.grug.minecraft.core.GrugCore;
import net.grug.minecraft.forge.block.GrugBlock;
import net.grug.minecraft.forge.block.entity.GrugBlockEntity;
import net.grug.minecraft.forge.gui.GrugMenu;
import net.grug.minecraft.forge.gui.GrugScreen;
import net.grug.minecraft.forge.resource.GrugPackResources;
import net.grug.minecraft.grug.FileInfo;
import net.grug.minecraft.grug.Grug;
import net.grug.minecraft.grug.GrugBlockData;
import net.grug.minecraft.grug.GrugItemData;
import net.grug.minecraft.gui.GrugGuiBuilder;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
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
import org.slf4j.Logger;

import java.io.File;
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

    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister
            .create(ForgeRegistries.BLOCK_ENTITY_TYPES, MODID);
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, MODID);
    public static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(ForgeRegistries.MENU_TYPES,
            MODID);
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS = DeferredRegister
            .create(Registries.CREATIVE_MODE_TAB, MODID);

    private static final Map<String, Long> blockFiles = new HashMap<>();
    private static final Map<String, Long> itemFiles = new HashMap<>();
    private static final List<RegistryObject<GrugBlock>> registeredGrugBlocks = new ArrayList<>();
    private static final List<RegistryObject<? extends Item>> registeredGrugItems = new ArrayList<>();

    public static final RegistryObject<MenuType<GrugMenu>> GRUG_MENU = MENUS.register("grug_menu",
            () -> IForgeMenuType.create((windowId, inv, data) -> {
                net.minecraft.core.BlockPos pos = data.readBlockPos();
                GrugGuiBuilder builder = GrugMenu.readBuilder(data);
                net.minecraft.world.Container container = (net.minecraft.world.Container) inv.player.level()
                        .getBlockEntity(pos);
                return new GrugMenu(null, windowId, inv, container, builder);
            }));

    public static final RegistryObject<CreativeModeTab> GRUG_TAB = CREATIVE_MODE_TABS.register("grug_tab",
            () -> CreativeModeTab.builder()
                    .title(Component.literal("Grug Mods"))
                    .icon(() -> !registeredGrugItems.isEmpty() ? new ItemStack(registeredGrugItems.get(0).get())
                            : new ItemStack(Blocks.STONE))
                    .displayItems((parameters, output) -> {
                        for (var itemReg : registeredGrugItems) {
                            output.accept(itemReg.get());
                        }
                    }).build());

    public static RegistryObject<BlockEntityType<GrugBlockEntity>> GRUG_BLOCK_ENTITY;

    public GrugModLoader() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

        initializeGrug();

        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        MENUS.register(modEventBus);
        CREATIVE_MODE_TABS.register(modEventBus);

        GRUG_BLOCK_ENTITY = BLOCK_ENTITIES.register("grug_block_entity", () -> {
            Block[] blockArray = registeredGrugBlocks.stream().map(RegistryObject::get).toArray(Block[]::new);
            return BlockEntityType.Builder.of(GrugBlockEntity::new, blockArray).build(null);
        });
        BLOCK_ENTITIES.register(modEventBus);

        modEventBus.addListener(this::onAddPackFinders);

        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(this);
    }

    public static File getActiveGrugModsDir() {
        File gameDir = FMLLoader.getGamePath().toFile();

        if (!FMLEnvironment.production) {
            File devGrugDir = new File(gameDir, "../../../core/src/main/resources/default_grug_mods");
            if (devGrugDir.exists() && devGrugDir.isDirectory()) {
                return devGrugDir;
            }
        }
        return new File(gameDir, "grug_mods");
    }

    private void onAddPackFinders(AddPackFindersEvent event) {
        PackType type = event.getPackType();
        PackLocationInfo info = new PackLocationInfo(
                "grug_resources_" + type.name(),
                Component.literal("Grug Mod " + (type == PackType.CLIENT_RESOURCES ? "Resources" : "Data")),
                PackSource.BUILT_IN,
                Optional.empty());
        Pack pack = Pack.readMetaAndCreate(
                info,
                new Pack.ResourcesSupplier() {
                    @Override
                    public PackResources openPrimary(PackLocationInfo locationInfo) {
                        return new GrugPackResources(locationInfo);
                    }

                    @Override
                    public PackResources openFull(PackLocationInfo locationInfo, Pack.Metadata metadata) {
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
            if (in != null) {
                Files.copy(in, modApiJson.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } else {
                LOGGER.error("Failed to load /mod_api.json resource from GrugCore classpath");
            }
        } catch (Exception e) {
            LOGGER.error("Failed to copy mod_api.json", e);
        }

        File activeGrugDir = getActiveGrugModsDir();

        GrugCore.initialize(new ForgeAdapter(), modApiJson, activeGrugDir);

        FileInfo[] files = Grug.compileAllFiles();

        for (FileInfo file : files) {
            if (file.fileId() == Grug.INVALID_GRUG_FILE_ID) {
                throw new RuntimeException("Failed to compile " + file.path() + ":\n" + file.errorString());
            }

            String[] pathParts = file.path().replace('\\', '/').split("/");
            if (pathParts.length < 2 || !pathParts[1].equals("code")) {
                throw new RuntimeException("Grug file misplaced! '" + file.path() + "' must be inside 'code/'.");
            }

            Grug.fileIds.put(file.path(), file.fileId());

            String cleanName = file.entityName().contains("-") ? file.entityName().split("-")[0] : file.entityName();

            if ("Block".equals(file.entityType())) {
                blockFiles.put(cleanName, file.fileId());
            } else if ("BlockEntity".equals(file.entityType())) {
                Grug.entityFileIdsByName.put(cleanName, file.fileId());
            } else if ("Item".equals(file.entityType())) {
                itemFiles.put(cleanName, file.fileId());
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

            var blockReg = BLOCKS.register(cleanName, () -> new GrugBlock(
                    BlockBehaviour.Properties.of().mapColor(MapColor.STONE).strength(blockData.hardness),
                    blockFileId));
            registeredGrugBlocks.add(blockReg);

            var itemReg = ITEMS.register(cleanName, () -> new BlockItem(blockReg.get(), new Item.Properties()));
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
            String[] updatedResources = Grug.update(LOGGER::error);
            for (String resource : updatedResources) {
                LOGGER.info("Reloading changed resource: {}", resource);
            }
        }
    }

    @Mod.EventBusSubscriber(modid = MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static class ClientModEvents {
        @SubscribeEvent
        public static void onClientSetup(FMLClientSetupEvent event) {
            event.enqueueWork(() -> {
                MenuScreens.register(GRUG_MENU.get(),
                        (GrugMenu menu, Inventory inv, Component title) -> new GrugScreen(menu, inv, title,
                                menu.layout));
            });
        }
    }
}
