package net.grug.minecraft.resource;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.grug.minecraft.events.init.InitListener;
import net.grug.minecraft.grug.Grug;
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

        if (path.startsWith("stationapi/textures/") || path.startsWith("stationapi/models/")
                || path.startsWith("stationapi/blockstates/")) {
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
            File[] modDirs = InitListener.getActiveGrugModsDir().listFiles(File::isDirectory);
            if (modDirs != null) {
                for (File modDir : modDirs) {
                    File file = new File(modDir, "data/" + id.getNamespace() + "/" + path);
                    if (file.exists())
                        return () -> new FileInputStream(file);
                }
            }
        }

        // Language files (Merged across all mods for the given namespace)
        if (path.startsWith("stationapi/lang/") && path.endsWith(".lang")) {
            return () -> {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                File[] modDirs = InitListener.getActiveGrugModsDir().listFiles(File::isDirectory);
                if (modDirs != null) {
                    for (File modDir : modDirs) {
                        File langFile = new File(modDir, "assets/" + id.getNamespace() + "/" + path);
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

        if (prefix.startsWith("stationapi/textures") || prefix.startsWith("stationapi/models")
                || prefix.startsWith("stationapi/blockstates") || prefix.startsWith("stationapi/lang")) {
            File[] modDirs = InitListener.getActiveGrugModsDir().listFiles(File::isDirectory);
            if (modDirs != null) {
                java.util.Set<Identifier> discoveredIds = new java.util.HashSet<>();
                for (File modDir : modDirs) {
                    File assetDir = new File(modDir, "assets/" + namespace + "/" + prefix);
                    if (assetDir.exists() && assetDir.isDirectory()) {
                        try (java.util.stream.Stream<Path> stream = Files.walk(assetDir.toPath())) {
                            stream.filter(Files::isRegularFile)
                                    .filter(p -> p.toString().endsWith(".png") || p.toString().endsWith(".json")
                                            || p.toString().endsWith(".lang"))
                                    .forEach(p -> {
                                        String relativePath = new File(modDir, "assets/" + namespace).toPath()
                                                .relativize(p).toString().replace('\\', '/');
                                        Identifier targetId = Identifier.of(namespace, relativePath);

                                        // Only yield the identifier once, as open() handles the merging!
                                        if (discoveredIds.add(targetId)) {
                                            InputSupplier<InputStream> supplier = this.open(type, targetId);
                                            if (supplier != null)
                                                consumer.accept(targetId, supplier);
                                        }
                                    });
                        } catch (Exception e) {
                            InitListener.LOGGER.error("Failed to walk asset directory", e);
                        }
                    }
                }
            }
        }

        if (prefix.startsWith("stationapi/recipes")) {
            File[] modDirs = InitListener.getActiveGrugModsDir().listFiles(File::isDirectory);
            if (modDirs != null) {
                for (File modDir : modDirs) {
                    File dataDir = new File(modDir, "data/" + namespace + "/" + prefix);
                    if (dataDir.exists() && dataDir.isDirectory()) {
                        try (java.util.stream.Stream<Path> stream = Files.walk(dataDir.toPath())) {
                            stream.filter(Files::isRegularFile)
                                    .filter(p -> p.toString().endsWith(".json"))
                                    .forEach(p -> {
                                        String relativePath = new File(modDir, "data/" + namespace).toPath()
                                                .relativize(p).toString().replace('\\', '/');
                                        Identifier targetId = Identifier.of(namespace, relativePath);
                                        InputSupplier<InputStream> supplier = this.open(type, targetId);
                                        if (supplier != null)
                                            consumer.accept(targetId, supplier);
                                    });
                        } catch (Exception e) {
                            InitListener.LOGGER.error("Failed to walk recipe directory", e);
                        }
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
