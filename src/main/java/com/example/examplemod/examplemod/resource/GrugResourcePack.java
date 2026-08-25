package com.example.examplemod.examplemod.resource;

import com.example.examplemod.examplemod.events.init.InitListener;
import com.example.examplemod.examplemod.grug.Grug;
import com.example.examplemod.examplemod.grug.GrugBlockData;
import net.fabricmc.loader.api.FabricLoader;
import net.modificationstation.stationapi.api.resource.InputSupplier;
import net.modificationstation.stationapi.api.resource.ResourcePack;
import net.modificationstation.stationapi.api.resource.ResourceType;
import net.modificationstation.stationapi.api.util.Identifier;
import net.modificationstation.stationapi.api.util.Namespace;
import net.modificationstation.stationapi.impl.resource.AbstractFileResourcePack;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Set;

public class GrugResourcePack extends AbstractFileResourcePack {

    public GrugResourcePack() {
        super("Grug Generated", true);
    }

    @Override
    public Set<Namespace> getNamespaces(ResourceType type) {
        return Collections.singleton(InitListener.NAMESPACE);
    }

    @Override
    public InputSupplier<InputStream> openRoot(String... segments) {
        return null;
    }

    @Override
    public InputSupplier<InputStream> open(ResourceType type, Identifier id) {
        if (id == null || !id.getNamespace().equals(InitListener.NAMESPACE)) {
            return null;
        }

        String path = id.getPath();

        // Synthesize language file if requested
        if (path.equals("stationapi/lang/en_US.lang")) {
            StringBuilder langFile = new StringBuilder();
            for (GrugBlockData block : Grug.declaredBlocks.values()) {
                if (block.displayName != null) {
                    langFile.append("tile.").append(InitListener.NAMESPACE).append(".").append(block.id.getPath())
                            .append(".name=").append(block.displayName).append("\n");
                }
            }
            return () -> new ByteArrayInputStream(langFile.toString().getBytes(StandardCharsets.UTF_8));
        }

        // Synthesize block/item assets
        for (GrugBlockData block : Grug.declaredBlocks.values()) {
            String blockPath = block.id.getPath(); // e.g. "foo_block"

            if (path.equals("stationapi/blockstates/" + blockPath + ".json")) {
                String json = "{ \"variants\": { \"\": { \"model\": \"" + InitListener.NAMESPACE + ":block/" + blockPath
                        + "\" } } }";
                return () -> new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
            } else if (path.equals("stationapi/models/block/" + blockPath + ".json")) {
                String json = "{ \"parent\": \"block/cube_all\", \"textures\": { \"all\": \"" + InitListener.NAMESPACE
                        + ":block/" + blockPath + "\" } }";
                return () -> new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
            } else if (path.equals("stationapi/models/item/" + blockPath + ".json")) {
                String json = "{ \"parent\": \"" + InitListener.NAMESPACE + ":block/" + blockPath + "\" }";
                return () -> new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
            } else if (path.equals("stationapi/textures/block/" + blockPath + ".png")) {
                if (block.texturePath != null) {
                    Path grugModsDir = FabricLoader.getInstance().getGameDir().resolve("grug_mods");
                    File textureFile = grugModsDir.resolve(block.texturePath).toFile();
                    if (textureFile.exists()) {
                        return () -> {
                            try {
                                return new FileInputStream(textureFile);
                            } catch (Exception e) {
                                return null;
                            }
                        };
                    }
                }
            }
        }

        return null;
    }

    @Override
    public void findResources(ResourceType type, Namespace namespace, String prefix,
            ResourcePack.ResultConsumer consumer) {
        if (!namespace.equals(InitListener.NAMESPACE)) {
            return;
        }

        // Yield lang file if available
        if (prefix.equals("stationapi/lang")) {
            Identifier targetId = Identifier.of(namespace, "stationapi/lang/en_US.lang");
            InputSupplier<InputStream> supplier = this.open(type, targetId);
            if (supplier != null)
                consumer.accept(targetId, supplier);
        }

        // Yield block assets
        for (GrugBlockData block : Grug.declaredBlocks.values()) {
            String blockPath = block.id.getPath();

            if (prefix.equals("stationapi/blockstates")) {
                Identifier targetId = Identifier.of(namespace, "stationapi/blockstates/" + blockPath + ".json");
                InputSupplier<InputStream> supplier = this.open(type, targetId);
                if (supplier != null)
                    consumer.accept(targetId, supplier);
            } else if (prefix.equals("stationapi/models")) {
                Identifier blockModelId = Identifier.of(namespace, "stationapi/models/block/" + blockPath + ".json");
                InputSupplier<InputStream> blockSupplier = this.open(type, blockModelId);
                if (blockSupplier != null)
                    consumer.accept(blockModelId, blockSupplier);

                Identifier itemModelId = Identifier.of(namespace, "stationapi/models/item/" + blockPath + ".json");
                InputSupplier<InputStream> itemSupplier = this.open(type, itemModelId);
                if (itemSupplier != null)
                    consumer.accept(itemModelId, itemSupplier);
            } else if (prefix.equals("stationapi/textures/block")) {
                Identifier targetId = Identifier.of(namespace, "stationapi/textures/block/" + blockPath + ".png");
                InputSupplier<InputStream> supplier = this.open(type, targetId);
                if (supplier != null) {
                    consumer.accept(targetId, supplier);
                }
            }
        }
    }

    @Override
    public void close() {
    }
}
