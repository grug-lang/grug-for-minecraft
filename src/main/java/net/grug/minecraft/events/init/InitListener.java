package net.grug.minecraft.events.init;

import net.grug.minecraft.block.GrugBlock;
import net.grug.minecraft.block.entity.GrugBlockEntity;
import net.grug.minecraft.grug.Grug;
import net.grug.minecraft.grug.GrugBlockData;
import net.grug.minecraft.grug.FileInfo;
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

    public static File getActiveGrugModsDir() {
        File gameDir = FabricLoader.getInstance().getGameDir().toFile();

        if (FabricLoader.getInstance().isDevelopmentEnvironment()) {
            File devGrugDir = new File(gameDir, "../src/main/resources/default_grug_mods");
            if (devGrugDir.exists() && devGrugDir.isDirectory()) {
                return devGrugDir;
            }
        }
        return new File(gameDir, "grug_mods");
    }

    @EventListener
    private static void registerBlocks(BlockRegistryEvent event) {
        File gameDir = FabricLoader.getInstance().getGameDir().toFile();
        File runGrugDir = new File(gameDir, "grug_mods");

        if (!runGrugDir.exists())
            runGrugDir.mkdirs();

        File modApiJson = new File(runGrugDir, "mod_api.json");

        File activeGrugDir = getActiveGrugModsDir();

        try {
            if (!activeGrugDir.getCanonicalPath().equals(runGrugDir.getCanonicalPath())) {
                LOGGER.info("Dev mode detected: Pointing grug-rs directly to " + activeGrugDir.getCanonicalPath());
            }

            try (InputStream in = InitListener.class.getResourceAsStream("/mod_api.json")) {
                if (in != null)
                    Files.copy(in, modApiJson.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }

            // Only extract if we are actually using the standard run folder
            if (activeGrugDir.getCanonicalPath().equals(runGrugDir.getCanonicalPath())) {
                extractDefaultGrugMods(runGrugDir.toPath());
            }

            Grug.init(modApiJson, activeGrugDir);

            FileInfo[] files = Grug.compileAllFiles();

            Map<String, Long> blockFiles = new HashMap<>();

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
                } else if ("BlockEntity".equals(file.entityType())) {
                    Grug.entityFileIdsByName.put(cleanName, file.fileId());
                }
            }

            // Synthesize and register the Block instances
            for (Map.Entry<String, Long> entry : blockFiles.entrySet()) {
                String cleanName = entry.getKey();

                Identifier blockId = Identifier.of(NAMESPACE, cleanName);

                long blockFileId = entry.getValue();

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

                new GrugBlock(blockId, blockFileId).setTranslationKey(blockId.namespace, blockId.path);
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
        Path markerFile = targetGrugDir.resolve(".examples_generated.txt");

        // If the marker file exists, the player already generated them.
        // We respect their right to delete the 'foo' folder without it coming back.
        if (Files.exists(markerFile)) {
            return;
        }

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

            // Write the marker file so we never forcefully extract again
            String msg = "This file tells the mod that the default examples have already been generated.\n" +
                    "If you delete the example folders, they won't come back.\n" +
                    "If you WANT the examples back, delete this file and restart the game.\n";
            Files.writeString(markerFile, msg);

        } catch (IOException e) {
            LOGGER.error("Failed to walk default_grug_mods directory", e);
        }
    }
}
