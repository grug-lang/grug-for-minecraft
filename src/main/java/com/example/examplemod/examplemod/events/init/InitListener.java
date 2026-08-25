package com.example.examplemod.examplemod.events.init;

import com.example.examplemod.examplemod.block.GrugBlock;
import com.example.examplemod.examplemod.block.entity.GrugBlockEntity;
import com.example.examplemod.examplemod.grug.Grug;
import com.example.examplemod.examplemod.grug.GrugBlockData;
import com.example.examplemod.examplemod.grug.FileInfo;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.mine_diver.unsafeevents.listener.EventListener;
import net.modificationstation.stationapi.api.event.block.entity.BlockEntityRegisterEvent;
import net.modificationstation.stationapi.api.event.mod.InitEvent;
import net.modificationstation.stationapi.api.event.registry.BlockRegistryEvent;
import net.modificationstation.stationapi.api.mod.entrypoint.EntrypointManager;
import net.modificationstation.stationapi.api.util.Identifier;
import net.modificationstation.stationapi.api.util.Namespace;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandles;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

public class InitListener {
    static {
        EntrypointManager.registerLookup(MethodHandles.lookup());
    }

    public static final Namespace NAMESPACE = Namespace.resolve();
    public static final Logger LOGGER = NAMESPACE.getLogger();

    @EventListener
    private static void serverInit(InitEvent event) {
        LOGGER.info(NAMESPACE.toString());
    }

    @EventListener
    private static void registerBlocks(BlockRegistryEvent event) {
        File gameDir = FabricLoader.getInstance().getGameDir().toFile();
        File grugDir = new File(gameDir, "grug_mods");

        if (!grugDir.exists())
            grugDir.mkdirs();

        File modApiJson = new File(grugDir, "mod_api.json");

        try {
            try (InputStream in = InitListener.class.getResourceAsStream("/mod_api.json")) {
                if (in != null)
                    Files.copy(in, modApiJson.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }

            extractDefaultGrugMods(grugDir.toPath());
            Grug.init(modApiJson, grugDir);

            FileInfo[] files = Grug.compileAllFiles();

            // Group blocks and their companion entities by their clean name (e.g.
            // "foo_block")
            Map<String, Long> blockFiles = new HashMap<>();
            Map<String, Long> entityFiles = new HashMap<>();
            Map<String, String> blockMods = new HashMap<>();

            for (FileInfo file : files) {
                if (file.fileId() == Grug.INVALID_GRUG_FILE_ID) {
                    LOGGER.error("Failed to compile {}: \n{}", file.path(), file.errorString());
                    continue;
                }

                Grug.fileIds.put(file.path(), file.fileId());

                String cleanName = file.entityName().contains("-") ? file.entityName().split("-")[0]
                        : file.entityName();

                if ("Block".equals(file.entityType())) {
                    blockFiles.put(cleanName, file.fileId());
                    blockMods.put(cleanName, file.modName());
                } else if ("BlockEntity".equals(file.entityType())) {
                    String baseName = cleanName.endsWith("_entity") ? cleanName.substring(0, cleanName.length() - 7)
                            : cleanName;
                    entityFiles.put(baseName, file.fileId());
                }
            }

            // Synthesize and register the Block instances
            for (Map.Entry<String, Long> entry : blockFiles.entrySet()) {
                String cleanName = entry.getKey();
                // FORCE the host namespace (examplemod) so StationAPI and AMI are happy
                Identifier blockId = Identifier.of(NAMESPACE, cleanName);
                String modName = blockMods.get(cleanName);

                long blockFileId = entry.getValue();
                long entityFileId = entityFiles.getOrDefault(cleanName, Grug.INVALID_GRUG_FILE_ID);

                GrugBlockData blockData = new GrugBlockData(blockId);
                Grug.currentlyInitializingBlock = blockData;

                long tempEntityHandle = Grug.createEntity(blockFileId);
                long initFnId = Grug.getExportFnId("Block", "init");

                if (tempEntityHandle != 0 && initFnId != Grug.INVALID_GRUG_EXPORT_FN_ID) {
                    Grug.callExportFn(tempEntityHandle, initFnId);
                }

                if (tempEntityHandle != 0) {
                    Grug.destroyEntity(tempEntityHandle);
                }

                Grug.declaredBlocks.put(blockId, blockData);
                Grug.blockDataByFileId.put(blockFileId, blockData);
                Grug.currentlyInitializingBlock = null;

                new GrugBlock(blockId, blockFileId, entityFileId).setTranslationKey(blockId.namespace, blockId.path);
                LOGGER.info("Registered Generic Grug Block: " + blockId + " (Grug Mod: " + modName + ")");
            }
        } catch (Exception e) {
            LOGGER.error("Failed to initialize grug-rs blocks", e);
        }
    }

    @EventListener
    private static void registerBlockEntities(BlockEntityRegisterEvent event) {
        // Register a single namespace alias that all GrugBlockEntity instances share
        event.register("grug:generic_block_entity", GrugBlockEntity.class);
    }

    private static void extractDefaultGrugMods(Path targetGrugDir) {
        Optional<ModContainer> modContainer = FabricLoader.getInstance().getModContainer(NAMESPACE.toString());
        if (modContainer.isEmpty())
            return;

        Optional<Path> defaultModsPath = modContainer.get().findPath("default_grug_mods");
        if (defaultModsPath.isEmpty())
            return;

        Path srcRoot = defaultModsPath.get();
        try (Stream<Path> stream = Files.walk(srcRoot)) {
            stream.forEach(source -> {
                try {
                    Path relative = srcRoot.relativize(source);
                    Path target = targetGrugDir.resolve(relative.toString());

                    if (Files.isDirectory(source)) {
                        if (!Files.exists(target))
                            Files.createDirectories(target);
                    } else {
                        if (!Files.exists(target))
                            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException e) {
                    LOGGER.error("Failed to extract default grug mod file: " + source, e);
                }
            });
        } catch (IOException e) {
            LOGGER.error("Failed to walk default_grug_mods directory", e);
        }
    }
}
