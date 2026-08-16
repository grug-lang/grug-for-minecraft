package com.example.examplemod.examplemod.events.init;

import com.example.examplemod.examplemod.block.ExampleBlock;
import net.mine_diver.unsafeevents.listener.EventListener;
import net.minecraft.block.Block;
import net.modificationstation.stationapi.api.event.mod.InitEvent;
import net.modificationstation.stationapi.api.event.registry.BlockRegistryEvent;
import net.modificationstation.stationapi.api.mod.entrypoint.Entrypoint;
import net.modificationstation.stationapi.api.mod.entrypoint.EntrypointManager;
import net.modificationstation.stationapi.api.util.Namespace;
import net.modificationstation.stationapi.api.util.Null;
import org.apache.logging.log4j.Logger;

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
    }

    @EventListener
    private static void registerBlocks(BlockRegistryEvent event) {
        exampleBlock = new ExampleBlock(NAMESPACE.id("example_block"))
                .setTranslationKey(NAMESPACE, "example_block");
    }
}
