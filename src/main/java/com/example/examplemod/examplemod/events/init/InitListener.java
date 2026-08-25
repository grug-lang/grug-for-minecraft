package com.example.examplemod.examplemod.events.init;

import com.example.examplemod.examplemod.block.FooBlock;
import com.example.examplemod.examplemod.block.entity.FooBlockEntity;
import com.example.examplemod.examplemod.grug.Grug;
import com.example.examplemod.examplemod.grug.GrugBlockData;
import com.example.examplemod.examplemod.grug.FileInfo;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.mine_diver.unsafeevents.listener.EventListener;
import net.minecraft.block.Block;
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
import java.util.Optional;
import java.util.stream.Stream;

public class InitListener {
    static {
        EntrypointManager.registerLookup(MethodHandles.lookup());
    }

    @SuppressWarnings("UnstableApiUsage")
    public static final Namespace NAMESPACE = Namespace.resolve();
    public static final Logger LOGGER = NAMESPACE.getLogger();
    public static Block fooBlock;

    @EventListener
    private static void serverInit(InitEvent event) {
        LOGGER.info(NAMESPACE.toString());

        File gameDir = FabricLoader.getInstance().getGameDir().toFile();
        File grugDir = new File(gameDir, "grug_mods");

        if (!grugDir.exists()) {
            grugDir.mkdirs();
        }

        File modApiJson = new File(grugDir, "mod_api.json");

        try {
            // Always sync mod_api.json so grug-rs matches
            // the current Java/C adapter signatures
            try (InputStream in = InitListener.class.getResourceAsStream("/mod_api.json")) {
                if (in != null) {
                    Files.copy(in, modApiJson.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
            }

            // Extract default .grug mods from resources if missing
            extractDefaultGrugMods(grugDir.toPath());

            // Initialize grug-rs
            Grug.init(modApiJson, grugDir);

            FileInfo[] files = Grug.compileAllFiles();
            for (FileInfo file : files) {
                if (file.fileId() == Grug.INVALID_GRUG_FILE_ID) {
                    LOGGER.error("Failed to compile {}: \n{}", file.path(), file.errorString());
                } else {
                    Grug.fileIds.put(file.path(), file.fileId());
                    LOGGER.info("Successfully compiled {} with file ID {}", file.path(), file.fileId());

                    // Run export init() for Blocks
                    if ("Block".equals(file.entityType())) {
                        Identifier blockId = Identifier.of(file.modName() + ":" + file.entityName());

                        GrugBlockData blockData = new GrugBlockData(blockId);
                        Grug.currentlyInitializingBlock = blockData;

                        long tempEntityHandle = Grug.createEntity(file.fileId());
                        long initFnId = Grug.getExportFnId("Block", "init");

                        if (tempEntityHandle != 0 && initFnId != Grug.INVALID_GRUG_EXPORT_FN_ID) {
                            Grug.callExportFn(tempEntityHandle, initFnId);
                        }

                        if (tempEntityHandle != 0) {
                            Grug.destroyEntity(tempEntityHandle);
                        }

                        Grug.declaredBlocks.put(blockId, blockData);
                        Grug.currentlyInitializingBlock = null;

                        LOGGER.info("Discovered Grug Block: " + blockId + " (Texture: " + blockData.texturePath
                                + ", Name: " + blockData.displayName + ")");
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.error("Failed to initialize grug-rs", e);
        }
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
                        if (!Files.exists(target)) {
                            Files.createDirectories(target);
                        }
                    } else {
                        // Only copy if the file doesn't exist so user edits aren't overwritten
                        if (!Files.exists(target)) {
                            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                } catch (IOException e) {
                    LOGGER.error("Failed to extract default grug mod file: " + source, e);
                }
            });
        } catch (IOException e) {
            LOGGER.error("Failed to walk default_grug_mods directory", e);
        }
    }

    @EventListener
    private static void registerBlocks(BlockRegistryEvent event) {
        fooBlock = new FooBlock(NAMESPACE.id("foo_block"))
                .setTranslationKey(NAMESPACE, "foo_block");
    }

    @EventListener
    private static void registerBlockEntities(BlockEntityRegisterEvent event) {
        event.register(NAMESPACE.id("foo_block").toString(), FooBlockEntity.class);
    }
}
