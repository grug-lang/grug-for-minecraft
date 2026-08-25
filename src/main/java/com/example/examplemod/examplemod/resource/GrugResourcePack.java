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
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
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
        if (id == null || !id.getNamespace().equals(InitListener.NAMESPACE))
            return null;

        String path = id.getPath();
        Path grugModsDir = FabricLoader.getInstance().getGameDir().resolve("grug_mods");

        // Concatenate language files safely
        if (path.equals("stationapi/lang/en_US.lang")) {
            return () -> {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                for (GrugBlockData block : Grug.declaredBlocks.values()) {
                    for (String langPath : block.langPaths) {
                        File langFile = grugModsDir.resolve(langPath).toFile();
                        if (langFile.exists()) {
                            try (FileInputStream fis = new FileInputStream(langFile)) {
                                fis.transferTo(out);
                                out.write('\n'); // Add a newline between files
                            } catch (Exception ignored) {
                            }
                        }
                    }
                }
                return new ByteArrayInputStream(out.toByteArray());
            };
        }

        // Return block/item assets from disk
        for (GrugBlockData block : Grug.declaredBlocks.values()) {
            String blockPath = block.id.getPath();

            if (path.equals("stationapi/blockstates/" + blockPath + ".json") && block.blockstatePath != null) {
                File file = grugModsDir.resolve(block.blockstatePath).toFile();
                if (file.exists())
                    return () -> new FileInputStream(file);
            } else if (path.equals("stationapi/models/block/" + blockPath + ".json") && block.blockModelPath != null) {
                File file = grugModsDir.resolve(block.blockModelPath).toFile();
                if (file.exists())
                    return () -> new FileInputStream(file);
            } else if (path.equals("stationapi/models/item/" + blockPath + ".json") && block.itemModelPath != null) {
                File file = grugModsDir.resolve(block.itemModelPath).toFile();
                if (file.exists())
                    return () -> new FileInputStream(file);
            } else if (path.equals("stationapi/textures/block/" + blockPath + ".png") && block.texturePath != null) {
                File file = grugModsDir.resolve(block.texturePath).toFile();
                if (file.exists())
                    return () -> new FileInputStream(file);
            }
        }
        return null;
    }

    @Override
    public void findResources(ResourceType type, Namespace namespace, String prefix,
            ResourcePack.ResultConsumer consumer) {
        if (!namespace.equals(InitListener.NAMESPACE))
            return;

        if (prefix.equals("stationapi/lang")) {
            Identifier targetId = Identifier.of(namespace, "stationapi/lang/en_US.lang");
            InputSupplier<InputStream> supplier = this.open(type, targetId);
            if (supplier != null)
                consumer.accept(targetId, supplier);
        }

        for (GrugBlockData block : Grug.declaredBlocks.values()) {
            String blockPath = block.id.getPath();

            if (prefix.equals("stationapi/blockstates") && block.blockstatePath != null) {
                Identifier targetId = Identifier.of(namespace, "stationapi/blockstates/" + blockPath + ".json");
                InputSupplier<InputStream> supplier = this.open(type, targetId);
                if (supplier != null)
                    consumer.accept(targetId, supplier);
            } else if (prefix.equals("stationapi/models")) {
                if (block.blockModelPath != null) {
                    Identifier blockModelId = Identifier.of(namespace,
                            "stationapi/models/block/" + blockPath + ".json");
                    InputSupplier<InputStream> blockSupplier = this.open(type, blockModelId);
                    if (blockSupplier != null)
                        consumer.accept(blockModelId, blockSupplier);
                }

                if (block.itemModelPath != null) {
                    Identifier itemModelId = Identifier.of(namespace, "stationapi/models/item/" + blockPath + ".json");
                    InputSupplier<InputStream> itemSupplier = this.open(type, itemModelId);
                    if (itemSupplier != null)
                        consumer.accept(itemModelId, itemSupplier);
                }
            } else if (prefix.equals("stationapi/textures/block") && block.texturePath != null) {
                Identifier targetId = Identifier.of(namespace, "stationapi/textures/block/" + blockPath + ".png");
                InputSupplier<InputStream> supplier = this.open(type, targetId);
                if (supplier != null)
                    consumer.accept(targetId, supplier);
            }
        }
    }

    @Override
    public void close() {
    }
}