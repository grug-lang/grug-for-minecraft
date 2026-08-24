package com.example.examplemod.examplemod.events.init;

import com.example.examplemod.examplemod.block.ExampleBlock;
import com.example.examplemod.examplemod.block.entity.ExampleBlockEntity;
import com.example.examplemod.examplemod.grug.FileInfo;
import com.example.examplemod.examplemod.grug.Grug;
import net.mine_diver.unsafeevents.listener.EventListener;
import net.minecraft.block.Block;
import net.modificationstation.stationapi.api.event.block.entity.BlockEntityRegisterEvent;
import net.modificationstation.stationapi.api.event.mod.InitEvent;
import net.modificationstation.stationapi.api.event.registry.BlockRegistryEvent;
import net.modificationstation.stationapi.api.mod.entrypoint.EntrypointManager;
import net.modificationstation.stationapi.api.util.Namespace;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.lang.invoke.MethodHandles;

public class InitListener {
    static {
        EntrypointManager.registerLookup(MethodHandles.lookup());
    }

    @SuppressWarnings("UnstableApiUsage")
    public static final Namespace NAMESPACE = Namespace.resolve();

    public static final Logger LOGGER = NAMESPACE.getLogger();

    public static Block exampleBlock;

    @EventListener
    private static void serverInit(InitEvent event) {
        LOGGER.info(NAMESPACE.toString());

        File runDir = new File(System.getProperty("user.dir"));
        File projectRoot = runDir.getName().equals("run") ? runDir.getParentFile() : runDir;

        File modApiJson = new File(projectRoot, "mod_api.json");
        File modsDir = new File(projectRoot, "mods");

        try {
            Grug.init(modApiJson, modsDir);

            FileInfo[] files = Grug.compileAllFiles();
            for (FileInfo file : files) {
                if (file.fileId() == Grug.INVALID_GRUG_FILE_ID) {
                    LOGGER.error("Failed to compile {}: \n{}", file.path(), file.errorString());
                } else {
                    LOGGER.info("Successfully compiled {} with file ID {}", file.path(), file.fileId());
                }
            }
        } catch (Exception e) {
            LOGGER.error("Failed to initialize grug-rs", e);
        }
    }

    @EventListener
    private static void registerBlocks(BlockRegistryEvent event) {
        exampleBlock = new ExampleBlock(NAMESPACE.id("example_block"))
                .setTranslationKey(NAMESPACE, "example_block");
    }

    @EventListener
    private static void registerBlockEntities(BlockEntityRegisterEvent event) {
        event.register(NAMESPACE.id("example_block").toString(), ExampleBlockEntity.class);
    }
}
