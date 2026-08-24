package com.example.examplemod.examplemod.events.init;

import com.example.examplemod.examplemod.block.FooBlock;
import com.example.examplemod.examplemod.block.entity.FooBlockEntity;
import com.example.examplemod.examplemod.grug.Grug;
import com.example.examplemod.examplemod.grug.FileInfo;
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
    public static Block fooBlock;

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
                    Grug.fileIds.put(file.path(), file.fileId());
                    LOGGER.info("Successfully compiled {} with file ID {}", file.path(), file.fileId());
                }
            }
        } catch (Exception e) {
            LOGGER.error("Failed to initialize grug-rs", e);
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
