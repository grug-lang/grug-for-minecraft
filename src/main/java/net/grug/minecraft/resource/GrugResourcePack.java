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
import java.nio.file.Files;
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
        return namespaces;
    }

    @Override
    public InputSupplier<InputStream> openRoot(String... segments) {
        return null;
    }

    @Override
    public InputSupplier<InputStream> open(ResourceType type, Identifier id) {
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

        if (path.startsWith("stationapi/textures/")) {
            File[] modDirs = InitListener.getActiveGrugModsDir().listFiles(File::isDirectory);
            if (modDirs != null) {
                for (File modDir : modDirs) {
                    File file = new File(modDir, "assets/" + id.getNamespace() + "/" + path);
                    if (file.exists())
                        return () -> new FileInputStream(file);
                }
            }
        }

        if (path.startsWith("stationapi/recipes/")) {
            String requestedSuffix = path.substring("stationapi/recipes/".length());
            for (String recipePath : Grug.declaredRecipes) {
                if (recipePath.endsWith("recipes/" + requestedSuffix)) {
                    File file = grugModsDir.resolve(recipePath).toFile();
                    if (file.exists())
                        return () -> new FileInputStream(file);
                }
            }
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
            }
        }

        // Return item assets
        for (GrugItemData item : Grug.declaredItems.values()) {
            String itemPath = item.id.getPath();

            if (path.equals("stationapi/models/item/" + itemPath + ".json") && item.itemModelPath != null) {
                File file = grugModsDir.resolve(item.itemModelPath).toFile();
                if (file.exists())
                    return () -> new FileInputStream(file);
            }
        }
        return null;
    }

    @Override
    public void findResources(ResourceType type, Namespace namespace, String prefix,
            ResourcePack.ResultConsumer consumer) {

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

        if (prefix.startsWith("stationapi/textures")) {
            File[] modDirs = InitListener.getActiveGrugModsDir().listFiles(File::isDirectory);
            if (modDirs != null) {
                for (File modDir : modDirs) {
                    File texDir = new File(modDir, "assets/" + namespace + "/" + prefix);
                    if (texDir.exists() && texDir.isDirectory()) {
                        try (java.util.stream.Stream<Path> stream = Files.walk(texDir.toPath())) {
                            stream.filter(Files::isRegularFile)
                                    .filter(p -> p.toString().endsWith(".png"))
                                    .forEach(p -> {
                                        String relativePath = new File(modDir, "assets/" + namespace).toPath()
                                                .relativize(p).toString().replace('\\', '/');
                                        Identifier targetId = Identifier.of(namespace, relativePath);
                                        InputSupplier<InputStream> supplier = this.open(type, targetId);
                                        if (supplier != null)
                                            consumer.accept(targetId, supplier);
                                    });
                        } catch (Exception e) {
                            InitListener.LOGGER.error("Failed to walk textures directory", e);
                        }
                    }
                }
            }
        }

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
            }
        }
    }

    private static InputSupplier<InputStream> mergeTagFragments(Path grugModsDir, Namespace namespace,
            String requestedSuffix) {
        JsonArray mergedValues = new JsonArray();
        boolean found = false;

        for (Grug.TagContribution contribution : Grug.declaredTags) {
            if (!namespaceOf(contribution.namespace()).equals(namespace))
                continue;

            if (contribution.path().endsWith(requestedSuffix)) {
                File file = grugModsDir.resolve(contribution.path()).toFile();
                if (!file.exists())
                    continue;

                found = true;
                try (FileReader reader = new FileReader(file)) {
                    JsonObject fragment = JsonParser.parseReader(reader).getAsJsonObject();
                    if (fragment.has("values")) {
                        for (var value : fragment.getAsJsonArray("values")) {
                            mergedValues.add(value);
                        }
                    }
                } catch (Exception e) {
                    InitListener.LOGGER.error("Failed to parse tag fragment: " + file, e);
                }
            }
        }

        if (!found) {
            return null;
        }

        JsonObject merged = new JsonObject();
        merged.add("values", mergedValues);
        byte[] bytes = merged.toString().getBytes(StandardCharsets.UTF_8);
        return () -> new ByteArrayInputStream(bytes);
    }

    @Override
    public void close() {
    }
}
