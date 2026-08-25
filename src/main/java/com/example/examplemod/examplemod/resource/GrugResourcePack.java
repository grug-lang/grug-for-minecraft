package com.example.examplemod.examplemod.resource;

import net.modificationstation.stationapi.api.resource.InputSupplier;
import net.modificationstation.stationapi.api.resource.ResourcePack;
import net.modificationstation.stationapi.api.resource.ResourceType;
import net.modificationstation.stationapi.api.util.Identifier;
import net.modificationstation.stationapi.api.util.Namespace;
import net.modificationstation.stationapi.impl.resource.AbstractFileResourcePack;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Set;

public class GrugResourcePack extends AbstractFileResourcePack {

    public GrugResourcePack() {
        super("Grug Generated", true);
    }

    @Override
    public Set<Namespace> getNamespaces(ResourceType type) {
        return Collections.singleton(Namespace.of("examplemod"));
    }

    @Override
    public InputSupplier<InputStream> openRoot(String... segments) {
        return null;
    }

    @Override
    public InputSupplier<InputStream> open(ResourceType type, Identifier id) {
        System.out.println("[GrugPack] open() called with ID: " + id);
        if (id != null && id.getPath().contains("my_dynamic_block")) {
            System.out.println("BINGO! StationAPI asked for our dynamic asset: " + id);

            String dummyJson = "{}";
            if (id.getPath().contains("blockstates")) {
                dummyJson = "{ \"variants\": { \"\": { \"model\": \"examplemod:block/my_dynamic_block\" } } }";
            } else if (id.getPath().contains("models")) {
                dummyJson = "{ \"parent\": \"block/cube_all\", \"textures\": { \"all\": \"examplemod:block/foo_block\" } }";
            }

            final String finalJson = dummyJson;
            return () -> new ByteArrayInputStream(finalJson.getBytes(StandardCharsets.UTF_8));
        }
        return null;
    }

    @Override
    public void findResources(ResourceType type, Namespace namespace, String prefix,
            ResourcePack.ResultConsumer consumer) {
        try {
            if (namespace.toString().equals("examplemod")) {
                System.out.println("[GrugPack] findResources namespace match! Prefix: " + prefix);

                if (prefix.equals("stationapi/blockstates")) {
                    System.out.println("[GrugPack] Processing blockstates");
                    Identifier targetId = Identifier.of(namespace, "stationapi/blockstates/my_dynamic_block.json");
                    System.out.println("[GrugPack] Created ID: " + targetId);
                    consumer.accept(targetId, this.open(type, targetId));
                    System.out.println("[GrugPack] Successfully fed blockstate to consumer");
                } else if (prefix.equals("stationapi/models")) {
                    System.out.println("[GrugPack] Processing models");
                    Identifier blockModelId = Identifier.of(namespace, "stationapi/models/block/my_dynamic_block.json");
                    consumer.accept(blockModelId, this.open(type, blockModelId));
                    Identifier itemModelId = Identifier.of(namespace, "stationapi/models/item/my_dynamic_block.json");
                    consumer.accept(itemModelId, this.open(type, itemModelId));
                    System.out.println("[GrugPack] Successfully fed models to consumer");
                }
            }
        } catch (Throwable t) {
            System.out.println("[GrugPack] THROWABLE CAUGHT in findResources:");
            t.printStackTrace();
        }
    }

    @Override
    public void close() {
    }
}
