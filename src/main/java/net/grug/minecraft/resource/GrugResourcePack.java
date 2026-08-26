package net.grug.minecraft.resource;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.grug.minecraft.events.init.InitListener;
import net.grug.minecraft.grug.Grug;
import net.grug.minecraft.grug.GrugBlockData;
import net.grug.minecraft.grug.GrugItemData;
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
import java.io.FileReader;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

public class GrugResourcePack extends AbstractFileResourcePack {

    public GrugResourcePack() {
        super("Grug Generated", true);
    }

    private static Namespace namespaceOf(String raw) {
        return Identifier.of(raw + ":_").getNamespace();
    }

    @Override
    public Set<Namespace> getNamespaces(ResourceType type) {
        Set<Namespace> namespaces = new HashSet<>();
        namespaces.add(InitListener.NAMESPACE);
        for (Grug.TagContribution contribution : Grug.declaredTags) {
            namespaces.add(namespaceOf(contribution.namespace()));
        }
        System.out.println("[DEBUG GRUG] getNamespaces(" + type + ") returning: " + namespaces);
        return namespaces;
    }

    @Override
    public InputSupplier<InputStream> openRoot(String... segments) {
        System.out.println("[DEBUG GRUG] openRoot() called with segments: " + String.join("/", segments));
        return null;
    }

    @Override
    public InputSupplier<InputStream> open(ResourceType type, Identifier id) {
        System.out.println("[DEBUG GRUG] open() called | type=" + type + " | id=" + id);

        if (id == null)
            return null;

        Path grugModsDir = InitListener.getActiveGrugModsDir().toPath();
        String path = id.getPath();

        if (path.startsWith("stationapi/tags/")) {
            String requestedSuffix = path.substring("stationapi/".length());
            return mergeTagFragments(grugModsDir, id.getNamespace(), requestedSuffix);
        }

        if (!id.getNamespace().equals(InitListener.NAMESPACE))
            return null;

        if (path.startsWith("stationapi/recipes/")) {
            String requestedSuffix = path.substring("stationapi/recipes/".length());
            System.out.println("[DEBUG GRUG] -> Extracted recipe suffix: " + requestedSuffix);
            for (String recipePath : Grug.declaredRecipes) {
                System.out.println("[DEBUG GRUG]   -> Checking against declared: " + recipePath);
                if (recipePath.endsWith("recipes/" + requestedSuffix)) {
                    File file = grugModsDir.resolve(recipePath).toFile();
                    System.out.println("[DEBUG GRUG]   -> MATCHED! Physical file exists? " + file.exists() + " at "
                            + file.getAbsolutePath());
                    if (file.exists())
                        return () -> new FileInputStream(file);
                }
            }
            System.out.println("[DEBUG GRUG] -> No matching recipe found for: " + requestedSuffix);
            return null;
        }

        // Language files
        if (path.equals("stationapi/lang/en_US.lang")) {
            return () -> {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                for (GrugBlockData block : Grug.declaredBlocks.values()) {
                    for (String langPath : block.langPaths) {
                        File langFile = grugModsDir.resolve(langPath).toFile();
                        if (langFile.exists()) {
                            try (FileInputStream fis = new FileInputStream(langFile)) {
                                fis.transferTo(out);
                                out.write('\n');
                            } catch (Exception ignored) {
                            }
                        }
                    }
                }
                for (GrugItemData item : Grug.declaredItems.values()) {
                    for (String langPath : item.langPaths) {
                        File langFile = grugModsDir.resolve(langPath).toFile();
                        if (langFile.exists()) {
                            try (FileInputStream fis = new FileInputStream(langFile)) {
                                fis.transferTo(out);
                                out.write('\n');
                            } catch (Exception ignored) {
                            }
                        }
                    }
                }
                return new ByteArrayInputStream(out.toByteArray());
            };
        }

        // Return block assets
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
            } else if (path.startsWith("stationapi/textures/")) {
                String requestedFile = path.substring(path.lastIndexOf('/') + 1);

                for (String texPath : block.textures) {
                    if (texPath.endsWith(requestedFile)) {
                        File file = grugModsDir.resolve(texPath).toFile();
                        if (file.exists())
                            return () -> new FileInputStream(file);
                    }
                }
            }
        }

        // Return item assets
        for (GrugItemData item : Grug.declaredItems.values()) {
            String itemPath = item.id.getPath();

            if (path.equals("stationapi/models/item/" + itemPath + ".json") && item.itemModelPath != null) {
                File file = grugModsDir.resolve(item.itemModelPath).toFile();
                if (file.exists())
                    return () -> new FileInputStream(file);
            } else if (path.startsWith("stationapi/textures/")) {
                String requestedFile = path.substring(path.lastIndexOf('/') + 1);

                for (String texPath : item.textures) {
                    if (texPath.endsWith(requestedFile)) {
                        File file = grugModsDir.resolve(texPath).toFile();
                        if (file.exists())
                            return () -> new FileInputStream(file);
                    }
                }
            }
        }
        return null;
    }

    @Override
    public void findResources(ResourceType type, Namespace namespace, String prefix,
            ResourcePack.ResultConsumer consumer) {

        System.out.println("[DEBUG GRUG] findResources() called | type=" + type + " | namespace=" + namespace
                + " | prefix=" + prefix);

        if (prefix.startsWith("stationapi/tags")) {
            Set<String> tagSuffixes = new LinkedHashSet<>();
            for (Grug.TagContribution contribution : Grug.declaredTags) {
                if (namespaceOf(contribution.namespace()).equals(namespace)) {
                    int tagsIdx = contribution.path().indexOf("tags/");
                    if (tagsIdx != -1) {
                        tagSuffixes.add(contribution.path().substring(tagsIdx));
                    }
                }
            }
            for (String tagSuffix : tagSuffixes) {
                Identifier targetId = Identifier.of(namespace, "stationapi/" + tagSuffix);
                if (targetId.getPath().startsWith(prefix)) {
                    InputSupplier<InputStream> supplier = this.open(type, targetId);
                    if (supplier != null)
                        consumer.accept(targetId, supplier);
                }
            }
        }

        if (!namespace.equals(InitListener.NAMESPACE))
            return;

        if (prefix.startsWith("stationapi/recipes")) {
            for (String recipePath : Grug.declaredRecipes) {
                int recipesIdx = recipePath.indexOf("recipes/");
                if (recipesIdx != -1) {
                    String subPath = recipePath.substring(recipesIdx);
                    Identifier targetId = Identifier.of(namespace, "stationapi/" + subPath);
                    if (targetId.getPath().startsWith(prefix)) {
                        InputSupplier<InputStream> supplier = this.open(type, targetId);
                        if (supplier != null)
                            consumer.accept(targetId, supplier);
                    }
                }
            }
        }

        if (prefix.startsWith("stationapi/lang")) {
            Identifier targetId = Identifier.of(namespace, "stationapi/lang/en_US.lang");
            if (targetId.getPath().startsWith(prefix)) {
                InputSupplier<InputStream> supplier = this.open(type, targetId);
                if (supplier != null)
                    consumer.accept(targetId, supplier);
            }
        }

        for (GrugBlockData block : Grug.declaredBlocks.values()) {
            String blockPath = block.id.getPath();

            if (prefix.startsWith("stationapi/blockstates") && block.blockstatePath != null) {
                Identifier targetId = Identifier.of(namespace, "stationapi/blockstates/" + blockPath + ".json");
                if (targetId.getPath().startsWith(prefix)) {
                    InputSupplier<InputStream> supplier = this.open(type, targetId);
                    if (supplier != null)
                        consumer.accept(targetId, supplier);
                }
            } else if (prefix.startsWith("stationapi/models")) {
                if (block.blockModelPath != null) {
                    Identifier blockModelId = Identifier.of(namespace,
                            "stationapi/models/block/" + blockPath + ".json");
                    if (blockModelId.getPath().startsWith(prefix)) {
                        InputSupplier<InputStream> blockSupplier = this.open(type, blockModelId);
                        if (blockSupplier != null)
                            consumer.accept(blockModelId, blockSupplier);
                    }
                }
                if (block.itemModelPath != null) {
                    Identifier itemModelId = Identifier.of(namespace, "stationapi/models/item/" + blockPath + ".json");
                    if (itemModelId.getPath().startsWith(prefix)) {
                        InputSupplier<InputStream> itemSupplier = this.open(type, itemModelId);
                        if (itemSupplier != null)
                            consumer.accept(itemModelId, itemSupplier);
                    }
                }
            } else if (prefix.startsWith("stationapi/textures")) {
                for (String texPath : block.textures) {
                    String textureType = texPath.contains("textures/item") ? "item" : "block";
                    String fileName = texPath.substring(texPath.lastIndexOf('/') + 1, texPath.length() - 4);
                    Identifier targetId = Identifier.of(namespace,
                            "stationapi/textures/" + textureType + "/" + fileName + ".png");
                    if (targetId.getPath().startsWith(prefix)) {
                        InputSupplier<InputStream> supplier = this.open(type, targetId);
                        if (supplier != null)
                            consumer.accept(targetId, supplier);
                    }
                }
            }
        }

        for (GrugItemData item : Grug.declaredItems.values()) {
            String itemPath = item.id.getPath();

            if (prefix.startsWith("stationapi/models")) {
                if (item.itemModelPath != null) {
                    Identifier itemModelId = Identifier.of(namespace, "stationapi/models/item/" + itemPath + ".json");
                    if (itemModelId.getPath().startsWith(prefix)) {
                        InputSupplier<InputStream> itemSupplier = this.open(type, itemModelId);
                        if (itemSupplier != null)
                            consumer.accept(itemModelId, itemSupplier);
                    }
                }
            } else if (prefix.startsWith("stationapi/textures")) {
                for (String texPath : item.textures) {
                    String textureType = texPath.contains("textures/item") ? "item" : "block";
                    String fileName = texPath.substring(texPath.lastIndexOf('/') + 1, texPath.length() - 4);
                    Identifier targetId = Identifier.of(namespace,
                            "stationapi/textures/" + textureType + "/" + fileName + ".png");
                    if (targetId.getPath().startsWith(prefix)) {
                        InputSupplier<InputStream> supplier = this.open(type, targetId);
                        if (supplier != null)
                            consumer.accept(targetId, supplier);
                    }
                }
            }
        }
    }

    private static InputSupplier<InputStream> mergeTagFragments(Path grugModsDir, Namespace namespace,
            String requestedSuffix) {
        System.out.println("[DEBUG GRUG] mergeTagFragments() called | namespace=" + namespace + " | requestedSuffix="
                + requestedSuffix);
        JsonArray mergedValues = new JsonArray();
        boolean found = false;

        for (Grug.TagContribution contribution : Grug.declaredTags) {
            if (!namespaceOf(contribution.namespace()).equals(namespace))
                continue;

            if (contribution.path().endsWith(requestedSuffix)) {
                File file = grugModsDir.resolve(contribution.path()).toFile();
                System.out.println(
                        "[DEBUG GRUG]   -> Tag Match! File exists? " + file.exists() + " at " + file.getAbsolutePath());
                if (!file.exists())
                    continue;

                found = true;
                try (FileReader reader = new FileReader(file)) {
                    JsonObject fragment = JsonParser.parseReader(reader).getAsJsonObject();
                    if (fragment.has("values")) {
                        for (var value : fragment.getAsJsonArray("values")) {
                            System.out.println("[DEBUG GRUG]     -> Adding tag value: " + value);
                            mergedValues.add(value);
                        }
                    }
                } catch (Exception e) {
                    InitListener.LOGGER.error("Failed to parse tag fragment: " + file, e);
                }
            }
        }

        if (!found) {
            System.out.println("[DEBUG GRUG] -> No valid tag fragments found.");
            return null;
        }

        JsonObject merged = new JsonObject();
        merged.add("values", mergedValues);
        System.out.println("[DEBUG GRUG] -> Returning merged JSON: " + merged.toString());
        byte[] bytes = merged.toString().getBytes(StandardCharsets.UTF_8);
        return () -> new ByteArrayInputStream(bytes);
    }

    @Override
    public void close() {
    }
}
