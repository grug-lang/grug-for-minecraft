package com.example.examplemod.examplemod.resource;

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
import java.util.HashSet;
import java.util.Set;

public class GrugResourcePack extends AbstractFileResourcePack {

    public GrugResourcePack() {
        super("Grug Generated", true);
    }

    @Override
    public Set<Namespace> getNamespaces(ResourceType type) {
        // Dynamically claim the namespace of every discovered Grug mod
        Set<Namespace> namespaces = new HashSet<>();
        for (Identifier id : Grug.declaredBlocks.keySet()) {
            namespaces.add(id.getNamespace());
        }
        return namespaces;
    }

    @Override
    public InputSupplier<InputStream> openRoot(String... segments) {
        return null;
    }

    @Override
    public InputSupplier<InputStream> open(ResourceType type, Identifier id) {
        String path = id.getPath();
        Namespace namespace = id.getNamespace();

        // Synthesize the Lang file (aggregates all blocks for this namespace)
        if (path.equals("stationapi/lang/en_US.lang")) {
            StringBuilder langFile = new StringBuilder();
            for (GrugBlockData block : Grug.declaredBlocks.values()) {
                if (block.id.getNamespace().equals(namespace) && block.displayName != null) {
                    langFile.append("tile.").append(block.id.getNamespace()).append(".").append(block.id.getPath())
                            .append(".name=").append(block.displayName).append("\n");
                }
            }
            return () -> new ByteArrayInputStream(langFile.toString().getBytes(StandardCharsets.UTF_8));
        }

        // Synthesize Block/Item assets dynamically
        for (GrugBlockData block : Grug.declaredBlocks.values()) {
            if (!block.id.getNamespace().equals(namespace))
                continue;

            String blockPath = block.id.getPath();

            if (path.equals("stationapi/blockstates/" + blockPath + ".json")) {
                String json = "{ \"variants\": { \"\": { \"model\": \"" + namespace + ":block/" + blockPath
                        + "\" } } }";
                return () -> new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
            } else if (path.equals("stationapi/models/block/" + blockPath + ".json")) {
                String json = "{ \"parent\": \"block/cube_all\", \"textures\": { \"all\": \"" + namespace + ":block/"
                        + blockPath + "\" } }";
                return () -> new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
            } else if (path.equals("stationapi/models/item/" + blockPath + ".json")) {
                String json = "{ \"parent\": \"" + namespace + ":block/" + blockPath + "\" }";
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
        // Expose our synthesized lang file
        if (prefix.equals("stationapi/lang")) {
            Identifier targetId = Identifier.of(namespace, "stationapi/lang/en_US.lang");
            consumer.accept(targetId, this.open(type, targetId));
        }

        // Expose the synthesized block assets
        for (GrugBlockData block : Grug.declaredBlocks.values()) {
            if (!block.id.getNamespace().equals(namespace))
                continue;

            String blockPath = block.id.getPath();

            if (prefix.equals("stationapi/blockstates")) {
                Identifier targetId = Identifier.of(namespace, "stationapi/blockstates/" + blockPath + ".json");
                consumer.accept(targetId, this.open(type, targetId));
            } else if (prefix.equals("stationapi/models")) {
                Identifier blockModelId = Identifier.of(namespace, "stationapi/models/block/" + blockPath + ".json");
                consumer.accept(blockModelId, this.open(type, blockModelId));

                Identifier itemModelId = Identifier.of(namespace, "stationapi/models/item/" + blockPath + ".json");
                consumer.accept(itemModelId, this.open(type, itemModelId));
            } else if (prefix.equals("stationapi/textures/block")) {
                Identifier targetId = Identifier.of(namespace, "stationapi/textures/block/" + blockPath + ".png");
                consumer.accept(targetId, this.open(type, targetId));
            }
        }
    }

    @Override
    public void close() {
    }
}
