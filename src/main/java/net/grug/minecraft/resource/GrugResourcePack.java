package net.grug.minecraft.resource;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.grug.minecraft.events.init.InitListener;
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

        File[] modDirs = InitListener.getActiveGrugModsDir().listFiles(File::isDirectory);
        if (modDirs != null) {
            for (File modDir : modDirs) {
                File typeDir = new File(modDir, type == ResourceType.SERVER_DATA ? "data" : "assets");
                File[] nsDirs = typeDir.listFiles(File::isDirectory);
                if (nsDirs != null) {
                    for (File nsDir : nsDirs) {
                        // Use your existing helper method here!
                        namespaces.add(namespaceOf(nsDir.getName()));
                    }
                }
            }
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

        String path = id.getPath();

        // Strip "stationapi/" when looking on the physical disk
        String diskPath = path.startsWith("stationapi/") ? path.substring(11) : path;

        // Tags
        if (path.startsWith("stationapi/tags/")) {
            return mergeTagFragments(id.getNamespace(), diskPath);
        }

        if (!id.getNamespace().equals(InitListener.NAMESPACE))
            return null;

        // Textures, Models, Blockstates
        if (path.startsWith("stationapi/textures/") || path.startsWith("stationapi/models/")
                || path.startsWith("stationapi/blockstates/")) {
            File[] modDirs = InitListener.getActiveGrugModsDir().listFiles(File::isDirectory);
            if (modDirs != null) {
                for (File modDir : modDirs) {
                    File file = new File(modDir, "assets/" + id.getNamespace() + "/" + diskPath);
                    if (file.exists())
                        return () -> new FileInputStream(file);
                }
            }
        }

        // Recipes
        if (path.startsWith("stationapi/recipes/")) {
            File[] modDirs = InitListener.getActiveGrugModsDir().listFiles(File::isDirectory);
            if (modDirs != null) {
                for (File modDir : modDirs) {
                    File file = new File(modDir, "data/" + id.getNamespace() + "/" + diskPath);
                    if (file.exists())
                        return () -> new FileInputStream(file);
                }
            }
        }

        // Language files
        if (path.startsWith("stationapi/lang/") && path.endsWith(".lang")) {
            return () -> {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                File[] modDirs = InitListener.getActiveGrugModsDir().listFiles(File::isDirectory);
                if (modDirs != null) {
                    for (File modDir : modDirs) {
                        File langFile = new File(modDir, "assets/" + id.getNamespace() + "/" + diskPath);
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

        // Strip "stationapi/" when looking on the physical disk
        String diskPrefix = prefix.startsWith("stationapi/") ? prefix.substring(11) : prefix;

        // Tags
        if (prefix.startsWith("stationapi/tags")) {
            File[] modDirs = InitListener.getActiveGrugModsDir().listFiles(File::isDirectory);
            if (modDirs != null) {
                java.util.Set<Identifier> discoveredIds = new java.util.HashSet<>();
                for (File modDir : modDirs) {
                    File dataDir = new File(modDir, "data/" + namespace + "/" + diskPrefix);
                    if (dataDir.exists() && dataDir.isDirectory()) {
                        try (java.util.stream.Stream<Path> stream = Files.walk(dataDir.toPath())) {
                            stream.filter(Files::isRegularFile)
                                    .filter(p -> p.toString().endsWith(".json"))
                                    .forEach(p -> {
                                        String relativePath = new File(modDir, "data/" + namespace).toPath()
                                                .relativize(p).toString().replace('\\', '/');
                                        Identifier targetId = Identifier.of(namespace, "stationapi/" + relativePath);
                                        if (discoveredIds.add(targetId)) {
                                            InputSupplier<InputStream> supplier = this.open(type, targetId);
                                            if (supplier != null)
                                                consumer.accept(targetId, supplier);
                                        }
                                    });
                        } catch (Exception e) {
                            InitListener.LOGGER.error("Failed to walk tag directory", e);
                        }
                    }
                }
            }
        }

        if (!namespace.equals(InitListener.NAMESPACE))
            return;

        // Textures, Models, Blockstates, Lang
        if (prefix.startsWith("stationapi/textures") || prefix.startsWith("stationapi/models")
                || prefix.startsWith("stationapi/blockstates") || prefix.startsWith("stationapi/lang")) {
            File[] modDirs = InitListener.getActiveGrugModsDir().listFiles(File::isDirectory);
            if (modDirs != null) {
                java.util.Set<Identifier> discoveredIds = new java.util.HashSet<>();
                for (File modDir : modDirs) {
                    File assetDir = new File(modDir, "assets/" + namespace + "/" + diskPrefix);
                    if (assetDir.exists() && assetDir.isDirectory()) {
                        try (java.util.stream.Stream<Path> stream = Files.walk(assetDir.toPath())) {
                            stream.filter(Files::isRegularFile)
                                    .filter(p -> p.toString().endsWith(".png") || p.toString().endsWith(".json")
                                            || p.toString().endsWith(".lang"))
                                    .forEach(p -> {
                                        String relativePath = new File(modDir, "assets/" + namespace).toPath()
                                                .relativize(p).toString().replace('\\', '/');
                                        Identifier targetId = Identifier.of(namespace, "stationapi/" + relativePath);
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

        // Recipes
        if (prefix.startsWith("stationapi/recipes")) {
            File[] modDirs = InitListener.getActiveGrugModsDir().listFiles(File::isDirectory);
            if (modDirs != null) {
                for (File modDir : modDirs) {
                    File dataDir = new File(modDir, "data/" + namespace + "/" + diskPrefix);
                    if (dataDir.exists() && dataDir.isDirectory()) {
                        try (java.util.stream.Stream<Path> stream = Files.walk(dataDir.toPath())) {
                            stream.filter(Files::isRegularFile)
                                    .filter(p -> p.toString().endsWith(".json"))
                                    .forEach(p -> {
                                        String relativePath = new File(modDir, "data/" + namespace).toPath()
                                                .relativize(p).toString().replace('\\', '/');
                                        Identifier targetId = Identifier.of(namespace, "stationapi/" + relativePath);
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

    private static InputSupplier<InputStream> mergeTagFragments(Namespace namespace, String diskPath) {
        JsonArray mergedValues = new JsonArray();
        boolean found = false;

        File[] modDirs = InitListener.getActiveGrugModsDir().listFiles(File::isDirectory);
        if (modDirs != null) {
            for (File modDir : modDirs) {
                File file = new File(modDir, "data/" + namespace + "/" + diskPath);
                if (file.exists()) {
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
